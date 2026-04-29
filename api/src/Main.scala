package familydns.api

import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.Clock
import zio.*
import zio.http.*
import zio.logging.*

object Main extends ZIOAppDefault:

  override val bootstrap =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  def run =
    for
      cfg <- ZIO.service[AppConfig]
      _   <- ZIO.logInfo(s"FamilyDNS API starting on ${cfg.http.host}:${cfg.http.port}")
      _   <- Database.runMigrations(cfg.db)
      _   <- ZIO.logInfo("Database migrations complete")
      _   <- Server
               .serve(allRoutes)
               .provide(
                 Server.defaultWithPort(cfg.http.port),
                 serverEnv,
               )
    yield ()

  private val serverEnv =
    AppConfig.layer >+>
    ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.db)) >+>
    ZLayer.fromZIO(ZIO.serviceWith[AppConfig](c => Database.makeTransactor(c.db))) >+>
    Repos.all >+>
    ZLayer.fromZIO(ZIO.serviceWith[AppConfig](_.jwt)) >+>
    AuthService.layer >+>
    Clock.live

  private def allRoutes =
    (for
      auth        <- ZIO.service[AuthService]
      userRepo    <- ZIO.service[UserRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      schedRepo   <- ZIO.service[ScheduleRepo]
      tlRepo      <- ZIO.service[TimeLimitRepo]
      stlRepo     <- ZIO.service[SiteTimeLimitRepo]
      deviceRepo  <- ZIO.service[DeviceRepo]
      blRepo      <- ZIO.service[BlocklistRepo]
      usageRepo   <- ZIO.service[TimeUsageRepo]
      extRepo     <- ZIO.service[TimeExtensionRepo]
      logRepo     <- ZIO.service[QueryLogRepo]
    yield
      AuthRoutes.routes(auth, userRepo) ++
      ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, stlRepo) ++
      DeviceRoutes.routes(auth, deviceRepo) ++
      TimeRoutes.routes(auth, deviceRepo, tlRepo, stlRepo, usageRepo, extRepo, profileRepo) ++
      LogRoutes.routes(auth, logRepo) ++
      BlocklistRoutes.routes(auth, blRepo)
    ).toRoutes
