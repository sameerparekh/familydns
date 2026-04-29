package familydns.testinfra

import doobie.Transactor
import doobie.hikari.HikariTransactor
import familydns.api.db.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.flywaydb.core.Flyway
import zio.*
import zio.interop.catz.*

import java.sql.Connection

/** Spins up a real embedded Postgres, runs Flyway migrations, provides a Transactor. */
object TestDatabase:

  /** A single embedded PG instance shared across the test suite. */
  val embeddedPg: ZLayer[Any, Throwable, EmbeddedPostgres] =
    ZLayer.scoped:
      ZIO.acquireRelease(
        ZIO.attempt(EmbeddedPostgres.start())
      )(pg => ZIO.succeed(pg.close()))

  /** Transactor wired to the embedded PG. */
  val transactor: ZLayer[EmbeddedPostgres, Throwable, Transactor[Task]] =
    ZLayer.fromZIO:
      for
        pg <- ZIO.service[EmbeddedPostgres]
        ds  = pg.getPostgresDatabase
        xa  = Transactor.fromDataSource[Task](ds, zio.interop.catz.asyncInstance)
      yield xa

  /** Run Flyway migrations against the embedded PG. */
  val migrate: ZLayer[EmbeddedPostgres, Throwable, Unit] =
    ZLayer.fromZIO:
      for
        pg <- ZIO.service[EmbeddedPostgres]
        _  <- ZIO.attempt:
                Flyway
                  .configure()
                  .dataSource(pg.getPostgresDatabase)
                  .locations("filesystem:api/src/db/migrations")
                  .cleanDisabled(false)
                  .load()
                  .migrate()
      yield ()

  /** Clean and re-migrate between tests so each test starts fresh. */
  val cleanAndMigrate: ZIO[EmbeddedPostgres, Throwable, Unit] =
    for
      pg <- ZIO.service[EmbeddedPostgres]
      _  <- ZIO.attempt:
              val fw = Flyway
                .configure()
                .dataSource(pg.getPostgresDatabase)
                .locations("filesystem:api/src/db/migrations")
                .cleanDisabled(false)
                .load()
              fw.clean()
              fw.migrate()
    yield ()

  /**
   * Full test layer: embedded PG + migrations + transactor + all repos.
   * Each test suite shares the PG instance. Individual tests call cleanAndMigrate
   * via beforeEach to get a clean slate.
   */
  val fullLayer: ZLayer[Any, Throwable, Transactor[Task] & AllRepos] =
    val pg = embeddedPg
    val xa = pg >>> transactor
    val mg = (pg ++ xa) >>> ZLayer.fromZIO(migrate.build.unit)
    xa >>> Repos.all

  /** All repo types bundled for convenience */
  type AllRepos =
    UserRepo &
    ProfileRepo &
    ScheduleRepo &
    TimeLimitRepo &
    SiteTimeLimitRepo &
    DeviceRepo &
    BlocklistRepo &
    TimeUsageRepo &
    TimeExtensionRepo &
    QueryLogRepo

  val layer: ZLayer[Any, Throwable, EmbeddedPostgres & Transactor[Task] & AllRepos] =
    val pg = embeddedPg
    val xa = pg >>> transactor
    pg ++ xa ++ (xa >>> Repos.all)

/** Helper for building test layers with a controllable clock. */
object TestLayers:
  import familydns.shared.Clock

  def withClock(dt: java.time.LocalDateTime): ULayer[Clock] =
    Clock.TestClock.make(dt)

  /** Seed helpers */
  def seedKidsProfile(profileRepo: ProfileRepo, scheduleRepo: ScheduleRepo): Task[Long] =
    for
      id <- profileRepo.create("Kids", List("adult", "gambling", "social_media"))
      _  <- scheduleRepo.replaceForProfile(id, List(
               familydns.shared.ScheduleRequest("Bedtime",
                 List("mon","tue","wed","thu","fri","sat","sun"), "21:00", "07:00")
             ))
    yield id

  def seedAdultsProfile(profileRepo: ProfileRepo): Task[Long] =
    profileRepo.create("Adults", List.empty)

  def seedDevice(
    deviceRepo: DeviceRepo,
    mac:        String,
    name:       String,
    profileId:  Long,
    location:   String = "home",
  ): Task[Long] =
    deviceRepo.upsert(mac, name, profileId, "192.168.1.100", location)
