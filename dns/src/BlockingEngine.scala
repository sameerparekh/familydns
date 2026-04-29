package familydns.dns

import familydns.shared.*
import zio.*

import java.time.{DayOfWeek, LocalDate, LocalTime}

/** Pure blocking logic — no effects, fully testable. */
object BlockingEngine:

  private val dayNames: Map[DayOfWeek, String] = Map(
    DayOfWeek.MONDAY    -> "mon",
    DayOfWeek.TUESDAY   -> "tue",
    DayOfWeek.WEDNESDAY -> "wed",
    DayOfWeek.THURSDAY  -> "thu",
    DayOfWeek.FRIDAY    -> "fri",
    DayOfWeek.SATURDAY  -> "sat",
    DayOfWeek.SUNDAY    -> "sun",
  )

  sealed trait Decision
  object Decision:
    case object Allow                  extends Decision
    case class Block(reason: String)   extends Decision

  /**
   * Full decision including blocklist lookup.
   *
   * Priority:
   *   1. Profile paused              → block:paused
   *   2. Schedule active             → block:schedule
   *   3. Domain in extra_allowed     → allow  (overrides everything below)
   *   4. Domain in extra_blocked     → block:extra_blocked
   *   5. Daily total time limit hit  → block:time_limit
   *   6. Site-specific limit hit     → block:site_time_limit:<label>
   *   7. Category blocklist match    → block:category:<cat>
   *   8. Default                     → allow
   */
  def decide(
    domain:     String,
    profile:    CachedProfile,
    blocklists: Map[String, Set[String]],
    usage:      TimeUsageSnapshot,
    mac:        String,
    now:        LocalTime,
    today:      LocalDate,
  ): Decision =
    val d = domain.toLowerCase.stripSuffix(".")

    if profile.profile.paused then return Decision.Block("paused")

    if isScheduleActive(profile.schedules, now, today) then return Decision.Block("schedule")

    if matchesAny(d, profile.profile.extraAllowed) then return Decision.Allow

    if matchesAny(d, profile.profile.extraBlocked) then return Decision.Block("extra_blocked")

    // Total daily time limit — site-specific domains are exempt
    profile.timeLimit.foreach { limitMins =>
      val isSiteDomain = profile.siteTimeLimits.exists(s => matchesDomainPattern(d, s.domainPattern))
      if !isSiteDomain then
        val usedMins = usage.totalUsage.getOrElse((mac, today.toString), 0)
        val extMins  = usage.extensions.getOrElse((mac, today.toString), 0)
        if usedMins >= limitMins + extMins then
          return Decision.Block("time_limit")
    }

    // Per-site time limits
    profile.siteTimeLimits.foreach { stl =>
      if matchesDomainPattern(d, stl.domainPattern) then
        val apex     = apexDomain(d)
        val usedMins = usage.domainUsage.getOrElse((mac, apex, today.toString), 0)
        if usedMins >= stl.dailyMinutes then
          return Decision.Block(s"site_time_limit:${stl.label}")
    }

    // Category blocklists
    profile.profile.blockedCategories.foreach { cat =>
      val list = blocklists.getOrElse(cat, Set.empty)
      if matchesDomainOrParent(d, list) then
        return Decision.Block(s"category:$cat")
    }

    Decision.Allow

  def isScheduleActive(schedules: List[Schedule], now: LocalTime, today: LocalDate): Boolean =
    val todayName = dayNames(today.getDayOfWeek)
    schedules.exists { s =>
      if !s.days.contains(todayName) then false
      else
        val from  = parseTime(s.blockFrom)
        val until = parseTime(s.blockUntil)
        if from.isAfter(until) then
          // Overnight: e.g. 21:00 → 07:00
          now.isAfter(from) || now.isBefore(until)
        else
          !now.isBefore(from) && now.isBefore(until)
    }

  def apexDomain(domain: String): String =
    val parts = domain.split('.')
    if parts.length >= 2 then parts.takeRight(2).mkString(".") else domain

  def matchesDomainPattern(domain: String, pattern: String): Boolean =
    if pattern.startsWith("*.") then
      val suffix = pattern.drop(1)
      domain.endsWith(suffix) || domain == pattern.drop(2)
    else
      domain == pattern || domain.endsWith(s".$pattern")

  private def matchesAny(domain: String, patterns: List[String]): Boolean =
    patterns.exists(p => matchesDomainPattern(domain, p))

  /** Check domain and all parent labels against blocklist set */
  private def matchesDomainOrParent(domain: String, list: Set[String]): Boolean =
    val parts = domain.split('.').toList
    (0 until parts.length - 1).exists(i => list.contains(parts.drop(i).mkString(".")))

  private def parseTime(s: String): LocalTime =
    val Array(h, m) = s.split(':')
    LocalTime.of(h.toInt, m.toInt)
