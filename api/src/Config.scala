package familydns.api

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

case class AppConfig(
    db: DbConfig,
    http: HttpConfig,
    jwt: JwtConfig,
    dns: DnsClientConfig,
)

case class DbConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int,
)

case class HttpConfig(
    host: String,
    port: Int,
    staticDir: String,
)

case class JwtConfig(
    secret: String,
    expiryHours: Int,
)

case class DnsClientConfig(
    cacheRefreshSeconds: Int,
    port: Int,
    location: String,
    upstreamPrimary: String,
    upstreamSecondary: String,
    upstreamPort: Int,
    logBatchSize: Int,
    logFlushSeconds: Int,
)

object AppConfig:
  val layer: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO:
      read(
        deriveConfig[AppConfig].from(
          TypesafeConfigProvider.fromHoconFile(
            new java.io.File("config/application.conf"),
          ),
        ),
      )
