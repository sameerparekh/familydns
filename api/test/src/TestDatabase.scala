package familydns.testinfra

import doobie.Transactor
import familydns.api.db.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.*
import zio.interop.catz.*

/**
 * Spins up a real embedded Postgres, runs Flyway migrations, provides a Transactor.
 *
 * A single EmbeddedPostgres instance is shared across all specs in the same JVM to avoid concurrent
 * initdb failures on ARM Mac (Postgres runs under Rosetta x86 emulation). Each test calls
 * cleanAndMigrate to reset state before running.
 */
object TestDatabase:

  /** JVM-wide singleton: started once, shared across all ZIOSpec bootstrap layers in this JVM. */
  private lazy val sharedPg: EmbeddedPostgres =
    val pg = EmbeddedPostgres.start()
    runMigrations(pg)
    pg

  /** Run V1__init.sql from the test resources directory on the classpath. */
  private[testinfra] def runMigrations(pg: EmbeddedPostgres): Unit =
    val url  = getClass.getResource("/V1__init.sql")
    if url == null then
      throw new RuntimeException(
        "V1__init.sql not found on classpath. Expected in api/test/resources/.",
      )
    val sql  = scala.io.Source.fromURL(url).mkString
    val conn = pg.getPostgresDatabase.getConnection
    try
      val stmt = conn.createStatement()
      sql.split(";").map(_.trim).filterNot(_.isEmpty).foreach(stmt.execute)
      stmt.close()
    finally conn.close()

  /** Provides the shared EmbeddedPostgres instance (no lifecycle: lives for JVM lifetime). */
  val embeddedPg: ZLayer[Any, Throwable, EmbeddedPostgres] =
    ZLayer.fromZIO(ZIO.attemptBlocking(sharedPg))

  /** Transactor wired to the embedded PG. */
  val transactor: ZLayer[EmbeddedPostgres, Throwable, Transactor[Task]] =
    ZLayer.fromZIO:
      for
        pg <- ZIO.service[EmbeddedPostgres]
        ds = pg.getPostgresDatabase
        xa = Transactor.fromDataSource[Task](ds, scala.concurrent.ExecutionContext.global)
      yield xa

  /**
   * Drop and recreate the public schema, then re-run migrations. This is faster and more reliable
   * than Flyway's clean() + migrate() in Flyway 10.
   */
  val cleanAndMigrate: ZIO[EmbeddedPostgres, Throwable, Unit] =
    for
      pg <- ZIO.service[EmbeddedPostgres]
      _  <- ZIO.attempt:
        val conn = pg.getPostgresDatabase.getConnection
        try
          conn.createStatement().execute("DROP SCHEMA public CASCADE")
          conn.createStatement().execute("CREATE SCHEMA public")
        finally conn.close()
      _  <- ZIO.attempt(runMigrations(pg))
    yield ()

  /** All repo types bundled for convenience */
  type AllRepos =
    UserRepo & ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo &
      BlocklistRepo & TimeUsageRepo & TimeExtensionRepo & QueryLogRepo

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
      _  <- scheduleRepo.replaceForProfile(
        id,
        List(
          familydns.shared.ScheduleRequest(
            "Bedtime",
            List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
            "21:00",
            "07:00",
          ),
        ),
      )
    yield id

  def seedAdultsProfile(profileRepo: ProfileRepo): Task[Long] =
    profileRepo.create("Adults", List.empty)

  def seedDevice(
      deviceRepo: DeviceRepo,
      mac: String,
      name: String,
      profileId: Long,
      location: String = "home",
  ): Task[Long] =
    deviceRepo.upsert(mac, name, profileId, "192.168.1.100", location)
