package familydns.dns.unit

import familydns.dns.BlockingEngine
import familydns.dns.BlockingEngine.Decision
import familydns.shared.*
import zio.test.*
import zio.test.Assertion.*

import java.time.{LocalDate, LocalTime}

/**
 * Unit tests for BlockingEngine pure functions.
 *
 * These cover edge cases in blocking logic — schedule boundary conditions, domain pattern matching
 * edge cases, priority ordering. No ZIO effects, no DB, no embedded Postgres.
 */
object BlockingEngineSpec extends ZIOSpecDefault {

  // ── Fixtures ──────────────────────────────────────────────────────────────

  private val monday   = LocalDate.of(2025, 1, 6) // Monday
  private val saturday = LocalDate.of(2025, 1, 11)

  private val bedtimeSchedule = Schedule(
    id = 1L,
    profileId = 1L,
    name = "Bedtime",
    days = List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
    blockFrom = "21:00",
    blockUntil = "07:00",
  )

  private val weekdayOnlySchedule = Schedule(
    id = 2L,
    profileId = 1L,
    name = "School",
    days = List("mon", "tue", "wed", "thu", "fri"),
    blockFrom = "08:00",
    blockUntil = "15:00",
  )

  private def profile(
      paused: Boolean = false,
      categories: List[String] = Nil,
      extra_blocked: List[String] = Nil,
      extra_allowed: List[String] = Nil,
      schedules: List[Schedule] = Nil,
      timeLimit: Option[Int] = None,
      siteTimeLimits: List[SiteTimeLimit] = Nil,
  ): CachedProfile = CachedProfile(
    profile = Profile(1L, "Kids", categories, extra_blocked, extra_allowed, paused),
    schedules = schedules,
    timeLimit = timeLimit,
    siteTimeLimits = siteTimeLimits,
  )

  private val emptyUsage = TimeUsageSnapshot.empty
  private val emptyLists = Map.empty[String, Set[String]]
  private val testMac    = "aa:bb:cc:dd:ee:ff"

  private def decide(
      domain: String,
      p: CachedProfile,
      lists: Map[String, Set[String]] = emptyLists,
      usage: TimeUsageSnapshot = emptyUsage,
      time: LocalTime = LocalTime.of(14, 0),
      date: LocalDate = monday,
  ): Decision =
    BlockingEngine.decide(domain, p, lists, usage, testMac, time, date)

  // ── Tests ─────────────────────────────────────────────────────────────────

  def spec = suite("BlockingEngine")(
    suite("paused profile")(
      test("blocks all domains when profile is paused") {
        val p = profile(paused = true)
        assertTrue(decide("google.com", p) == Decision.Block("paused")) &&
        assertTrue(decide("pbs.org", p) == Decision.Block("paused")) &&
        assertTrue(decide("anything.com", p) == Decision.Block("paused"))
      },
      test("blocks even extra_allowed domains when paused") {
        val p = profile(paused = true, extra_allowed = List("khanacademy.org"))
        assertTrue(decide("khanacademy.org", p) == Decision.Block("paused"))
      },
    ),
    suite("schedule")(
      test("blocks during overnight schedule (21:00-07:00)") {
        val p = profile(schedules = List(bedtimeSchedule))
        assertTrue(
          decide("google.com", p, time = LocalTime.of(21, 30)) == Decision.Block("schedule"),
        ) &&
        assertTrue(
          decide("google.com", p, time = LocalTime.of(23, 59)) == Decision.Block("schedule"),
        ) &&
        assertTrue(
          decide("google.com", p, time = LocalTime.of(0, 0)) == Decision.Block("schedule"),
        ) &&
        assertTrue(
          decide("google.com", p, time = LocalTime.of(6, 59)) == Decision.Block("schedule"),
        )
      },
      test("allows just after overnight schedule ends") {
        val p = profile(schedules = List(bedtimeSchedule))
        assertTrue(decide("google.com", p, time = LocalTime.of(7, 0)) == Decision.Allow) &&
        assertTrue(decide("google.com", p, time = LocalTime.of(7, 1)) == Decision.Allow)
      },
      test("allows during the day when schedule is not active") {
        val p = profile(schedules = List(bedtimeSchedule))
        assertTrue(decide("google.com", p, time = LocalTime.of(14, 0)) == Decision.Allow) &&
        assertTrue(decide("google.com", p, time = LocalTime.of(20, 59)) == Decision.Allow)
      },
      test("weekday-only schedule does not apply on Saturday") {
        val p = profile(schedules = List(weekdayOnlySchedule))
        assertTrue(
          decide("google.com", p, time = LocalTime.of(10, 0), date = saturday) == Decision.Allow,
        )
      },
      test("weekday-only schedule applies on Monday") {
        val p = profile(schedules = List(weekdayOnlySchedule))
        assertTrue(
          decide("google.com", p, time = LocalTime.of(10, 0), date = monday) == Decision.Block(
            "schedule",
          ),
        )
      },
      test("schedule boundary: exactly at block_from is blocked") {
        val p = profile(schedules = List(bedtimeSchedule))
        assertTrue(
          decide("google.com", p, time = LocalTime.of(21, 0)) == Decision.Block("schedule"),
        )
      },
      test("schedule boundary: exactly at block_until is allowed") {
        val p = profile(schedules = List(bedtimeSchedule))
        assertTrue(decide("google.com", p, time = LocalTime.of(7, 0)) == Decision.Allow)
      },
    ),
    suite("extra_allowed / extra_blocked")(
      test("extra_allowed overrides category blocklist") {
        val lists = Map("adult" -> Set("khanacademy.org")) // miscategorised
        val p     = profile(categories = List("adult"), extra_allowed = List("khanacademy.org"))
        assertTrue(decide("khanacademy.org", p, lists = lists) == Decision.Allow)
      },
      test("extra_blocked blocks even if not in any category") {
        val p = profile(extra_blocked = List("distracting.com"))
        assertTrue(decide("distracting.com", p) == Decision.Block("extra_blocked"))
      },
      test("extra_allowed does NOT override schedule") {
        val p = profile(
          schedules = List(bedtimeSchedule),
          extra_allowed = List("khanacademy.org"),
        )
        // At 22:00 (bedtime) khanacademy should still be blocked by schedule
        assertTrue(
          decide("khanacademy.org", p, time = LocalTime.of(22, 0)) == Decision.Block("schedule"),
        )
      },
    ),
    suite("domain pattern matching")(
      test("exact domain matches") {
        assertTrue(BlockingEngine.matchesDomainPattern("youtube.com", "youtube.com"))
      },
      test("subdomain of exact pattern matches") {
        assertTrue(BlockingEngine.matchesDomainPattern("www.youtube.com", "youtube.com"))
      },
      test("wildcard pattern matches subdomain") {
        assertTrue(BlockingEngine.matchesDomainPattern("www.youtube.com", "*.youtube.com"))
      },
      test("wildcard apex itself matches") {
        assertTrue(BlockingEngine.matchesDomainPattern("youtube.com", "*.youtube.com"))
      },
      test("different domain does not match") {
        assertTrue(!BlockingEngine.matchesDomainPattern("vimeo.com", "youtube.com"))
      },
      test("partial suffix does not match") {
        assertTrue(!BlockingEngine.matchesDomainPattern("notyoutube.com", "youtube.com"))
      },
      test("deeper subdomain matches wildcard") {
        assertTrue(BlockingEngine.matchesDomainPattern("cdn.static.youtube.com", "*.youtube.com"))
      },
    ),
    suite("apex domain extraction")(
      test("www.youtube.com → youtube.com") {
        assertTrue(BlockingEngine.apexDomain("www.youtube.com") == "youtube.com")
      },
      test("youtube.com → youtube.com") {
        assertTrue(BlockingEngine.apexDomain("youtube.com") == "youtube.com")
      },
      test("cdn.static.youtube.com → youtube.com") {
        assertTrue(BlockingEngine.apexDomain("cdn.static.youtube.com") == "youtube.com")
      },
      test("localhost → localhost") {
        assertTrue(BlockingEngine.apexDomain("localhost") == "localhost")
      },
    ),
    suite("category blocklist")(
      test("blocks domain in blocked category") {
        val lists = Map("adult" -> Set("pornhub.com", "xvideos.com"))
        val p     = profile(categories = List("adult"))
        assertTrue(decide("pornhub.com", p, lists = lists) == Decision.Block("category:adult"))
      },
      test("does not block domain in non-blocked category") {
        val lists = Map("social_media" -> Set("facebook.com"))
        val p     = profile(categories = List("adult")) // social_media not in profile
        assertTrue(decide("facebook.com", p, lists = lists) == Decision.Allow)
      },
      test("blocks subdomain via parent domain in list") {
        val lists = Map("adult" -> Set("badsite.com"))
        val p     = profile(categories = List("adult"))
        assertTrue(decide("www.badsite.com", p, lists = lists) == Decision.Block("category:adult"))
      },
      test("does not block if domain not in list") {
        val lists = Map("adult" -> Set("pornhub.com"))
        val p     = profile(categories = List("adult"))
        assertTrue(decide("google.com", p, lists = lists) == Decision.Allow)
      },
    ),
    suite("time limits")(
      test("blocks when daily limit is reached") {
        val p     = profile(timeLimit = Some(120))
        val usage = TimeUsageSnapshot(
          domainUsage = Map.empty,
          totalUsage = Map((testMac, monday.toString) -> 120),
          extensions = Map.empty,
        )
        assertTrue(decide("google.com", p, usage = usage) == Decision.Block("time_limit"))
      },
      test("allows when under daily limit") {
        val p     = profile(timeLimit = Some(120))
        val usage = TimeUsageSnapshot(
          domainUsage = Map.empty,
          totalUsage = Map((testMac, monday.toString) -> 119),
          extensions = Map.empty,
        )
        assertTrue(decide("google.com", p, usage = usage) == Decision.Allow)
      },
      test("extension adds to effective limit") {
        val p     = profile(timeLimit = Some(120))
        val usage = TimeUsageSnapshot(
          domainUsage = Map.empty,
          totalUsage = Map((testMac, monday.toString) -> 130),
          extensions = Map((testMac, monday.toString) -> 30), // +30 extension
        )
        // 130 used, limit is 120+30=150 → should allow
        assertTrue(decide("google.com", p, usage = usage) == Decision.Allow)
      },
      test("site-specific domain exempt from total limit") {
        val stl   = SiteTimeLimit(1L, 1L, "*.youtube.com", 30, "YouTube")
        val p     = profile(timeLimit = Some(120), siteTimeLimits = List(stl))
        val usage = TimeUsageSnapshot(
          domainUsage = Map.empty,
          totalUsage = Map((testMac, monday.toString) -> 125), // over limit
          extensions = Map.empty,
        )
        // youtube.com is site-specific, so NOT blocked by total time limit
        assertTrue(decide("youtube.com", p, usage = usage) != Decision.Block("time_limit"))
      },
      test("blocks when site-specific limit reached") {
        val stl   = SiteTimeLimit(1L, 1L, "*.youtube.com", 30, "YouTube")
        val p     = profile(timeLimit = Some(120), siteTimeLimits = List(stl))
        val usage = TimeUsageSnapshot(
          domainUsage = Map((testMac, "youtube.com", monday.toString) -> 30),
          totalUsage = Map.empty,
          extensions = Map.empty,
        )
        assertTrue(
          decide("youtube.com", p, usage = usage) == Decision.Block("site_time_limit:YouTube"),
        )
      },
      test("site-specific limit does not block different site") {
        val stl   = SiteTimeLimit(1L, 1L, "*.youtube.com", 30, "YouTube")
        val p     = profile(timeLimit = Some(120), siteTimeLimits = List(stl))
        val usage = TimeUsageSnapshot(
          domainUsage = Map((testMac, "youtube.com", monday.toString) -> 30),
          totalUsage = Map.empty,
          extensions = Map.empty,
        )
        assertTrue(decide("vimeo.com", p, usage = usage) == Decision.Allow)
      },
    ),
    suite("priority ordering")(
      test("paused takes priority over schedule") {
        val p = profile(paused = true, schedules = List(bedtimeSchedule))
        assertTrue(decide("google.com", p, time = LocalTime.of(14, 0)) == Decision.Block("paused"))
      },
      test("extra_allowed takes priority over extra_blocked") {
        // If somehow same domain appears in both lists, allowed wins
        val p = profile(extra_allowed = List("example.com"), extra_blocked = List("example.com"))
        assertTrue(decide("example.com", p) == Decision.Allow)
      },
      test("extra_allowed takes priority over category") {
        val lists = Map("adult" -> Set("example.com"))
        val p     = profile(categories = List("adult"), extra_allowed = List("example.com"))
        assertTrue(decide("example.com", p, lists = lists) == Decision.Allow)
      },
    ),
  )
}
