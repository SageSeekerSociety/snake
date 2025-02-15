package org.rucca.cheese.auth.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EnableJpaRepositories("org.rucca.cheese.auth")
@EntityScan("org.rucca.cheese.auth")
class JpaConfig {}
