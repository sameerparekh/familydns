import mill._
import mill.scalalib._
import mill.scalalib.scalafmt.ScalafmtModule

val scala3Version = "3.3.3"

val zioVersion         = "2.1.9"
val zioHttpVersion     = "3.0.1"
val zioJsonVersion     = "0.7.3"
val zioConfigVersion   = "4.0.2"
val zioLoggingVersion  = "2.3.1"
val doobieVersion      = "1.0.0-RC5"
val flywayVersion      = "10.17.3"
val jwtVersion         = "10.0.1"
val bcryptVersion      = "0.10.2"
val postgresVersion    = "42.7.4"
val logbackVersion     = "1.5.8"
val pcap4jVersion      = "1.8.2"
val embeddedPgVersion  = "2.1.0"
val flywayPgVersion    = "10.17.3"

trait CommonModule extends ScalaModule with ScalafmtModule {
  def scalaVersion = scala3Version
  def scalacOptions = Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:all",
    "-Wvalue-discard",
    "-Xfatal-warnings",
    "-source:future",
  )
}

trait FamilyDnsTestModule extends TestModule.ZioTest {
  def testFramework = "zio.test.sbt.ZTestFramework"
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"dev.zio::zio-test:$zioVersion",
    ivy"dev.zio::zio-test-sbt:$zioVersion",
    ivy"dev.zio::zio-test-magnolia:$zioVersion",
    ivy"io.zonky.test:embedded-postgres:$embeddedPgVersion",
    ivy"org.flywaydb:flyway-core:$flywayVersion",
    ivy"org.flywaydb:flyway-database-postgresql:$flywayPgVersion",
    ivy"org.postgresql:postgresql:$postgresVersion",
    ivy"ch.qos.logback:logback-classic:$logbackVersion",
  )
}

// ── Shared ─────────────────────────────────────────────────────────────────
object shared extends CommonModule {
  def ivyDeps = Agg(
    ivy"dev.zio::zio:$zioVersion",
    ivy"dev.zio::zio-json:$zioJsonVersion",
  )
  object test extends ScalaTests with FamilyDnsTestModule
}

// ── API ────────────────────────────────────────────────────────────────────
object api extends CommonModule {
  def moduleDeps = Seq(shared)
  def ivyDeps = Agg(
    ivy"dev.zio::zio:$zioVersion",
    ivy"dev.zio::zio-streams:$zioVersion",
    ivy"dev.zio::zio-http:$zioHttpVersion",
    ivy"dev.zio::zio-json:$zioJsonVersion",
    ivy"dev.zio::zio-config:$zioConfigVersion",
    ivy"dev.zio::zio-config-typesafe:$zioConfigVersion",
    ivy"dev.zio::zio-config-magnolia:$zioConfigVersion",
    ivy"dev.zio::zio-logging:$zioLoggingVersion",
    ivy"dev.zio::zio-logging-slf4j:$zioLoggingVersion",
    ivy"org.tpolecat::doobie-core:$doobieVersion",
    ivy"org.tpolecat::doobie-postgres:$doobieVersion",
    ivy"org.tpolecat::doobie-hikari:$doobieVersion",
    ivy"org.flywaydb:flyway-core:$flywayVersion",
    ivy"org.flywaydb:flyway-database-postgresql:$flywayPgVersion",
    ivy"com.github.jwt-scala::jwt-core:$jwtVersion",
    ivy"com.github.jwt-scala::jwt-zio-json:$jwtVersion",
    ivy"at.favre.lib:bcrypt:$bcryptVersion",
    ivy"org.postgresql:postgresql:$postgresVersion",
    ivy"ch.qos.logback:logback-classic:$logbackVersion",
  )
  def resources = T.sources {
    super.resources() ++ Seq(PathRef(os.pwd / "api" / "src" / "db" / "migrations"))
  }
  object test extends ScalaTests with FamilyDnsTestModule {
    def moduleDeps = super.moduleDeps ++ Seq(api)
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"dev.zio::zio-http-testkit:$zioHttpVersion",
    )
  }
}

// ── DNS ────────────────────────────────────────────────────────────────────
object dns extends CommonModule {
  def moduleDeps = Seq(shared)
  def ivyDeps = Agg(
    ivy"dev.zio::zio:$zioVersion",
    ivy"dev.zio::zio-streams:$zioVersion",
    ivy"dev.zio::zio-config:$zioConfigVersion",
    ivy"dev.zio::zio-config-typesafe:$zioConfigVersion",
    ivy"dev.zio::zio-config-magnolia:$zioConfigVersion",
    ivy"dev.zio::zio-logging:$zioLoggingVersion",
    ivy"dev.zio::zio-logging-slf4j:$zioLoggingVersion",
    ivy"org.tpolecat::doobie-core:$doobieVersion",
    ivy"org.tpolecat::doobie-postgres:$doobieVersion",
    ivy"org.tpolecat::doobie-hikari:$doobieVersion",
    ivy"org.postgresql:postgresql:$postgresVersion",
    ivy"ch.qos.logback:logback-classic:$logbackVersion",
  )
  object test extends ScalaTests with FamilyDnsTestModule {
    def moduleDeps = super.moduleDeps ++ Seq(dns)
  }
}

// ── Traffic ────────────────────────────────────────────────────────────────
object traffic extends CommonModule {
  def moduleDeps = Seq(shared)
  def ivyDeps = Agg(
    ivy"dev.zio::zio:$zioVersion",
    ivy"dev.zio::zio-streams:$zioVersion",
    ivy"dev.zio::zio-config:$zioConfigVersion",
    ivy"dev.zio::zio-config-typesafe:$zioConfigVersion",
    ivy"dev.zio::zio-config-magnolia:$zioConfigVersion",
    ivy"dev.zio::zio-logging:$zioLoggingVersion",
    ivy"dev.zio::zio-logging-slf4j:$zioLoggingVersion",
    ivy"org.pcap4j:pcap4j-core:$pcap4jVersion",
    ivy"org.pcap4j:pcap4j-packetfactory-static:$pcap4jVersion",
    ivy"org.tpolecat::doobie-core:$doobieVersion",
    ivy"org.tpolecat::doobie-postgres:$doobieVersion",
    ivy"org.tpolecat::doobie-hikari:$doobieVersion",
    ivy"org.postgresql:postgresql:$postgresVersion",
    ivy"ch.qos.logback:logback-classic:$logbackVersion",
  )
  object test extends ScalaTests with FamilyDnsTestModule {
    def moduleDeps = super.moduleDeps ++ Seq(traffic)
  }
}
