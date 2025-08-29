package org.rucca.snake.controller.config

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.codec.ByteArrayCodec
import java.time.Duration
import kotlin.jvm.optionals.getOrNull
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

@Configuration
class RateLimiterConfig {
    @Bean(destroyMethod = "shutdown")
    fun bucket4jLettuceClient(cf: LettuceConnectionFactory): AbstractRedisClient {
        val cluster = cf.clusterConfiguration
        return if (cluster == null) {
            val sc = cf.standaloneConfiguration
            val uri =
                RedisURI.Builder.redis(sc.hostName, sc.port)
                    .apply {
                        withDatabase(sc.database)
                        val username = sc.username
                        val password = sc.password.toOptional().getOrNull()
                        if (username != null && password != null) {
                            withAuthentication(username, password)
                        } else if (password != null) {
                            withPassword(password)
                        }
                        withSsl(cf.isUseSsl)
                    }
                    .build()
            RedisClient.create(uri)
        } else {
            val uris =
                cluster.clusterNodes.map {
                    RedisURI.Builder.redis(it.host, it.port ?: 6379)
                        // TODO: support authentication
                        .build()
                }
            RedisClusterClient.create(uris)
        }
    }

    @Bean(destroyMethod = "close")
    fun bucket4jRedisConnection(
        bucket4jLettuceClient: AbstractRedisClient
    ): StatefulConnection<ByteArray, ByteArray> =
        when (bucket4jLettuceClient) {
            is RedisClient -> bucket4jLettuceClient.connect(ByteArrayCodec.INSTANCE)
            is RedisClusterClient -> bucket4jLettuceClient.connect(ByteArrayCodec.INSTANCE)
            else -> error("Unsupported Lettuce client")
        }

    @Bean
    fun redisProxyManager(
        bucket4jRedisConnection: StatefulConnection<ByteArray, ByteArray>
    ): ProxyManager<ByteArray> {
        val builder =
            when (bucket4jRedisConnection) {
                is StatefulRedisConnection<ByteArray, ByteArray> ->
                    Bucket4jLettuce.casBasedBuilder(bucket4jRedisConnection)

                is StatefulRedisClusterConnection<ByteArray, ByteArray> ->
                    Bucket4jLettuce.casBasedBuilder(bucket4jRedisConnection)

                else -> error("Unsupported Lettuce stateful connection type")
            }

        return builder
            .expirationAfterWrite(
                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                    Duration.ofSeconds(10)
                )
            )
            .build()
    }
}
