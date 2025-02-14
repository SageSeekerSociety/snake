/*
 *  Description: This file defines the application configuration properties.
 *               It is used to read the properties from src/main/resources/application.properties
 *
 *  Author(s):
 *      Nictheboy Li    <nictheboy@outlook.com>
 *
 */

package org.rucca.cheese.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "application")
class ApplicationConfig {
    lateinit var compilerPath: String
    lateinit var compilerParameter: String
    lateinit var dataDirectory: String
}
