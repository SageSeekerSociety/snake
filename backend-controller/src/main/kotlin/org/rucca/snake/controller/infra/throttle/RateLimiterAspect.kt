package org.rucca.snake.controller.infra.throttle

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.ConsumptionProbe
import io.github.bucket4j.distributed.proxy.ProxyManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.rucca.cheese.auth.AuthenticationService
import org.slf4j.LoggerFactory
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@Aspect
@Component
class RateLimiterAspect(
    private val redisProxyManager: ProxyManager<ByteArray>,
    private val authenticationService: AuthenticationService,
    private val properties: RateLimiterProperties,
) {
    private val logger = LoggerFactory.getLogger(RateLimiterAspect::class.java)
    private val expressionParser = SpelExpressionParser()
    private val expressionCache = ConcurrentHashMap<String, Expression>()

    @Around(
        "@annotation(org.rucca.snake.controller.infra.throttle.RateLimited) || @annotation(org.rucca.snake.controller.infra.throttle.RateLimits)"
    )
    fun rateLimit(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method

        val policyNames = mutableListOf<String>()
        method.getAnnotation(RateLimits::class.java)?.let { anno ->
            policyNames.addAll(anno.value.map { it.value })
        }
        method.getAnnotation(RateLimited::class.java)?.let { anno -> policyNames.add(anno.value) }

        if (policyNames.isEmpty()) return joinPoint.proceed()

        val context = createEvaluationContext(joinPoint)

        var mostRelevantProbe: ConsumptionProbe? = null
        var mostRelevantPolicy: Policy? = null

        for (policyName in policyNames) {
            val policy =
                properties.policies[policyName]
                    ?: throw IllegalStateException(
                        "Rate limiting policy '$policyName' not found in configuration."
                    )

            val key = parseSpel(policy.keyExpression, context, String::class.java)
            val tokensToConsume = parseSpel(policy.tokensExpression, context, Long::class.java)

            if (key == null || tokensToConsume == null || tokensToConsume == 0L) {
                continue
            }

            val configuration =
                BucketConfiguration.builder()
                    .addLimit { limit ->
                        limit
                            .capacity(policy.capacity)
                            .refillIntervally(
                                policy.refillRate,
                                Duration.of(policy.refillPeriod, policy.refillUnit),
                            )
                    }
                    .build()

            val bucket = redisProxyManager.getProxy(key.toByteArray()) { configuration }
            val probe = bucket.tryConsumeAndReturnRemaining(tokensToConsume)

            if (probe.isConsumed) {
                if (
                    mostRelevantProbe == null ||
                        (probe.remainingTokens * 100 / policy.capacity) <
                            (mostRelevantProbe.remainingTokens * 100 /
                                mostRelevantPolicy!!.capacity)
                ) {
                    mostRelevantProbe = probe
                    mostRelevantPolicy = policy
                }
            } else {
                setRateLimitHeaders(probe, policy)
                throw RateLimitExceededException("Rate limit exceeded.", probe.nanosToWaitForRefill)
            }
        }

        setRateLimitHeaders(mostRelevantProbe, mostRelevantPolicy)
        return joinPoint.proceed()
    }

    private fun setRateLimitHeaders(probe: ConsumptionProbe?, policy: Policy?) {
        if (probe == null || policy == null) return
        val response =
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.response
                ?: return

        response.setHeader("RateLimit-Limit", policy.capacity.toString())
        response.setHeader("RateLimit-Remaining", probe.remainingTokens.toString())
        val resetSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.nanosToWaitForRefill)
        response.setHeader("RateLimit-Reset", resetSeconds.toString())
    }

    private fun createEvaluationContext(joinPoint: ProceedingJoinPoint): StandardEvaluationContext {
        val context = StandardEvaluationContext()
        val signature = joinPoint.signature as MethodSignature
        val parameterNames = signature.parameterNames
        val args = joinPoint.args

        for (i in parameterNames.indices) {
            context.setVariable(parameterNames[i], args[i])
        }

        context.setVariable("userId", authenticationService.getCurrentUserId())
        return context
    }

    private fun <T> parseSpel(
        expressionString: String,
        context: StandardEvaluationContext,
        desiredResultType: Class<T>,
    ): T? {
        return try {
            val expression =
                expressionCache.computeIfAbsent(expressionString) {
                    expressionParser.parseExpression(it)
                }
            expression.getValue(context, desiredResultType)
        } catch (e: Exception) {
            logger.error("Failed to parse SpEL expression: '${expressionString}'", e)
            null
        }
    }
}
