package familydns.shared

import zio.*

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneId}

/** Injectable clock — never call java.time directly outside this service. */
trait Clock:
  def now: UIO[LocalDateTime]
  def today: UIO[LocalDate]
  def currentTime: UIO[LocalTime]

object Clock:

  // ── Accessors (call these everywhere) ───────────────────────────────────

  def now: URIO[Clock, LocalDateTime]  = ZIO.serviceWithZIO(_.now)
  def today: URIO[Clock, LocalDate]    = ZIO.serviceWithZIO(_.today)
  def currentTime: URIO[Clock, LocalTime] = ZIO.serviceWithZIO(_.currentTime)

  // ── Live implementation ──────────────────────────────────────────────────

  val live: ULayer[Clock] = ZLayer.succeed(LiveClock())

  private class LiveClock extends Clock:
    def now: UIO[LocalDateTime]  = ZIO.succeed(LocalDateTime.now())
    def today: UIO[LocalDate]    = ZIO.succeed(LocalDate.now())
    def currentTime: UIO[LocalTime] = ZIO.succeed(LocalTime.now())

  // ── Controllable test implementation ────────────────────────────────────

  /** A clock backed by a Ref — advance time programmatically in tests. */
  class TestClock(ref: Ref[LocalDateTime]) extends Clock:
    def now: UIO[LocalDateTime]     = ref.get
    def today: UIO[LocalDate]       = ref.get.map(_.toLocalDate)
    def currentTime: UIO[LocalTime] = ref.get.map(_.toLocalTime)

    /** Advance the clock by the given duration. */
    def advance(duration: java.time.Duration): UIO[Unit] =
      ref.update(_.plus(duration))

    /** Set the clock to a specific datetime. */
    def setTo(dt: LocalDateTime): UIO[Unit] =
      ref.set(dt)

    /** Set just the time component, keeping the date. */
    def setTime(t: LocalTime): UIO[Unit] =
      ref.update(dt => dt.toLocalDate.atTime(t))

    /** Set just the date, keeping the time. */
    def setDate(d: LocalDate): UIO[Unit] =
      ref.update(dt => d.atTime(dt.toLocalTime))

  object TestClock:
    /** Create a TestClock starting at the given datetime. */
    def at(dt: LocalDateTime): ULayer[Clock & TestClock] =
      ZLayer.fromZIO(
        Ref.make(dt).map(r => new TestClock(r))
      ).project(tc => tc: Clock).merge(
        ZLayer.fromZIO(
          Ref.make(dt).map(r => new TestClock(r))
        )
      )

    /**
     * Simpler API: make(dt) returns both Clock and TestClock as the same instance.
     * Usage: TestClock.make(...) >>> yourLayer
     */
    def make(dt: LocalDateTime): ULayer[Clock] =
      ZLayer.fromZIO(
        Ref.make(dt).map(new TestClock(_))
      )

    def makeWithControl(dt: LocalDateTime): UIO[(Clock, TestClock)] =
      Ref.make(dt).map { ref =>
        val tc = new TestClock(ref)
        (tc: Clock, tc)
      }

    /** Standard school-day test time: Monday 14:00 */
    val schoolDayAfternoon: LocalDateTime =
      LocalDateTime.of(2025, 1, 6, 14, 0, 0) // Monday 2pm

    /** Bedtime: Monday 21:30 */
    val bedtime: LocalDateTime =
      LocalDateTime.of(2025, 1, 6, 21, 30, 0)

    /** Early morning: Monday 06:00 */
    val earlyMorning: LocalDateTime =
      LocalDateTime.of(2025, 1, 6, 6, 0, 0)

    /** Weekend afternoon: Saturday 15:00 */
    val weekendAfternoon: LocalDateTime =
      LocalDateTime.of(2025, 1, 11, 15, 0, 0)
