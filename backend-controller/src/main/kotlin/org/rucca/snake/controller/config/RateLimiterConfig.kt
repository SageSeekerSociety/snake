package org.rucca.snake.controller.config

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.codec.ByteArrayCodec
import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

@Configuration
class RateLimiterConfig {

    @Bean(destroyMethod = "close")
    fun bucket4jRedisConnection(
        cf: LettuceConnectionFactory
    ): StatefulConnection<ByteArray, ByteArray> {
        val cluster = cf.clusterConfiguration
        return if (cluster == null) {
            val sc = cf.standaloneConfiguration
            val uri = RedisURI.Builder.redis(sc.hostName, sc.port).withDatabase(sc.database).build()
            val client = RedisClient.create(uri)
            client.connect(ByteArrayCodec.INSTANCE)
        } else {
            // cluster
            val uris =
                cluster.clusterNodes.map {
                    RedisURI.Builder.redis(it.host, it.port ?: 6379).build()
                }
            val client = RedisClusterClient.create(uris)
            client.connect(ByteArrayCodec.INSTANCE)
        }
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
