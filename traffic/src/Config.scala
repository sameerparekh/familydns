package familydns.traffic

import familydns.api.DbConfig
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

case class TrafficAppConfig(
    db: DbConfig,
    traffic: TrafficConfig,
)

object TrafficAppConfig:
  val layer: ZLayer[Any, Config.Error, TrafficAppConfig] =
    ZLayer.fromZIO:
      read(
        deriveConfig[TrafficAppConfig].from(
          TypesafeConfigProvider.fromHoconFile(
            new java.io.File("config/application.conf"),
          ),
        ),
      )
