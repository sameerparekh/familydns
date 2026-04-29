package familydns.api.routes

import familydns.api.auth.*
import familydns.api.db.*
import familydns.shared.*
import zio.*
import zio.http.*
import zio.json.*

import java.time.LocalDate

// ── Auth routes ────────────────────────────────────────────────────────────

object AuthRoutes:
  def routes(auth: AuthService, userRepo: UserRepo): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "auth" / "login" ->
        handler { (req: Request) =>
          for
            body <- req.body.asString.mapError(e => Response.internalServerError(e.getMessage))
            lr   <- ZIO.fromEither(body.fromJson[LoginRequest])
                      .mapError(e => Response.badRequest(e))
            resp <- auth.login(lr.username, lr.password)
                      .mapError {
                        case AuthError.InvalidCredentials => Response.unauthorized("Invalid credentials")
                        case e => Response.internalServerError(e.toString)
                      }
          yield Response.json(resp.toJson)
        },

      Method.POST / "api" / "auth" / "change-password" ->
        handler { (req: Request) =>
          for
            claims <- requireAuth(req, auth)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            cpr    <- ZIO.fromEither(body.fromJson[ChangePasswordRequest]).mapError(e => Response.badRequest(e))
            _      <- auth.changePassword(claims.sub, cpr.currentPassword, cpr.newPassword)
                        .mapError {
                          case AuthError.InvalidCredentials => Response.unauthorized("Current password incorrect")
                          case e => Response.internalServerError(e.toString)
                        }
          yield Response.ok,
        },

      Method.POST / "api" / "users" ->
        handler { (req: Request) =>
          for
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            cur  <- ZIO.fromEither(body.fromJson[CreateUserRequest]).mapError(e => Response.badRequest(e))
            hash <- auth.hashPassword(cur.password)
            id   <- userRepo.create(cur.username, hash, cur.role).orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"id":$id}""")
        },

      Method.GET / "api" / "users" ->
        handler { (req: Request) =>
          for
            _     <- requireAdmin(req, auth)
            users <- userRepo.listAll.orElseFail(Response.internalServerError(""))
            json   = users.map(u => s"""{"id":${u.id},"username":"${u.username}","role":"${u.role}"}""")
          yield Response.json(s"[${json.mkString(",")}]")
        },

      Method.DELETE / "api" / "users" / long("id") ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
          userRepo.delete(id).orElseFail(Response.internalServerError("")) *>
          ZIO.succeed(Response.ok)
        },
    )

// ── Profile routes ─────────────────────────────────────────────────────────

object ProfileRoutes:
  def routes(
    auth:          AuthService,
    profileRepo:   ProfileRepo,
    scheduleRepo:  ScheduleRepo,
    timeLimitRepo: TimeLimitRepo,
    siteTimeLimitRepo: SiteTimeLimitRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "profiles" ->
        handler { (req: Request) =>
          for
            _        <- requireAuth(req, auth)
            profiles <- profileRepo.listAll.orElseFail(Response.internalServerError(""))
            details  <- ZIO.foreach(profiles) { p =>
                          for
                            scheds <- scheduleRepo.listForProfile(p.id)
                            tl     <- timeLimitRepo.findForProfile(p.id)
                            stls   <- siteTimeLimitRepo.listForProfile(p.id)
                          yield ProfileDetail(p, scheds, tl, stls)
                        }.orElseFail(Response.internalServerError(""))
          yield Response.json(details.toJson)
        },

      Method.GET / "api" / "profiles" / long("id") ->
        handler { (id: Long, req: Request) =>
          for
            _      <- requireAuth(req, auth)
            p      <- profileRepo.findById(id).orElseFail(Response.internalServerError(""))
                        .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
            scheds <- scheduleRepo.listForProfile(id).orElseFail(Response.internalServerError(""))
            tl     <- timeLimitRepo.findForProfile(id).orElseFail(Response.internalServerError(""))
            stls   <- siteTimeLimitRepo.listForProfile(id).orElseFail(Response.internalServerError(""))
          yield Response.json(ProfileDetail(p, scheds, tl, stls).toJson)
        },

      Method.POST / "api" / "profiles" ->
        handler { (req: Request) =>
          for
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            upr  <- ZIO.fromEither(body.fromJson[UpsertProfileRequest]).mapError(e => Response.badRequest(e))
            id   <- profileRepo.create(upr.name, upr.blockedCategories)
                      .orElseFail(Response.internalServerError(""))
            _    <- scheduleRepo.replaceForProfile(id, upr.schedules)
                      .orElseFail(Response.internalServerError(""))
            _    <- ZIO.foreachDiscard(upr.timeLimit)(mins =>
                      timeLimitRepo.upsert(id, mins)).orElseFail(Response.internalServerError(""))
            _    <- siteTimeLimitRepo.replaceForProfile(id, upr.siteTimeLimits)
                      .orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"id":$id}""")
        },

      Method.PUT / "api" / "profiles" / long("id") ->
        handler { (id: Long, req: Request) =>
          for
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            upr  <- ZIO.fromEither(body.fromJson[UpsertProfileRequest]).mapError(e => Response.badRequest(e))
            p    <- profileRepo.findById(id).orElseFail(Response.internalServerError(""))
                      .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
            _    <- profileRepo.update(p.copy(
                      name              = upr.name,
                      blockedCategories = upr.blockedCategories,
                      extraBlocked      = upr.extraBlocked,
                      extraAllowed      = upr.extraAllowed,
                      paused            = upr.paused,
                    )).orElseFail(Response.internalServerError(""))
            _    <- scheduleRepo.replaceForProfile(id, upr.schedules)
                      .orElseFail(Response.internalServerError(""))
            _    <- (upr.timeLimit match
                      case Some(mins) => timeLimitRepo.upsert(id, mins)
                      case None       => timeLimitRepo.delete(id)
                    ).orElseFail(Response.internalServerError(""))
            _    <- siteTimeLimitRepo.replaceForProfile(id, upr.siteTimeLimits)
                      .orElseFail(Response.internalServerError(""))
          yield Response.ok
        },

      Method.DELETE / "api" / "profiles" / long("id") ->
        handler { (id: Long, req: Request) =>
          requireAdmin(req, auth) *>
          profileRepo.delete(id).orElseFail(Response.internalServerError("")) *>
          ZIO.succeed(Response.ok)
        },

      Method.POST / "api" / "profiles" / long("id") / "pause" ->
        handler { (id: Long, req: Request) =>
          for
            _  <- requireAdmin(req, auth)
            p  <- profileRepo.findById(id).orElseFail(Response.internalServerError(""))
                    .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("")))
            _  <- profileRepo.setPaused(id, !p.paused).orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"paused":${!p.paused}}""")
        },
    )

// ── Device routes ──────────────────────────────────────────────────────────

object DeviceRoutes:
  def routes(auth: AuthService, deviceRepo: DeviceRepo): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "devices" ->
        handler { (req: Request) =>
          requireAuth(req, auth) *>
          deviceRepo.listAll.map(ds => Response.json(ds.toJson))
            .orElseFail(Response.internalServerError(""))
        },

      Method.PUT / "api" / "devices" ->
        handler { (req: Request) =>
          for
            _    <- requireAdmin(req, auth)
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            udr  <- ZIO.fromEither(body.fromJson[UpsertDeviceRequest]).mapError(e => Response.badRequest(e))
            mac   = normalizeMac(udr.mac)
            id   <- deviceRepo.upsert(mac, udr.name, udr.profileId, "", udr.location.getOrElse("home"))
                      .orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"id":$id}""")
        },

      Method.DELETE / "api" / "devices" / string("mac") ->
        handler { (mac: String, req: Request) =>
          requireAdmin(req, auth) *>
          deviceRepo.delete(normalizeMac(mac)).orElseFail(Response.internalServerError("")) *>
          ZIO.succeed(Response.ok)
        },
    )

// ── Time routes ────────────────────────────────────────────────────────────

object TimeRoutes:
  def routes(
    auth:         AuthService,
    deviceRepo:   DeviceRepo,
    timeLimitRepo: TimeLimitRepo,
    siteTimeLimitRepo: SiteTimeLimitRepo,
    usageRepo:    TimeUsageRepo,
    extRepo:      TimeExtensionRepo,
    profileRepo:  ProfileRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "time" / "status" ->
        handler { (req: Request) =>
          for
            claims  <- requireAuth(req, auth)
            dateStr  = req.url.queryParam("date").getOrElse(LocalDate.now().toString)
            date     = LocalDate.parse(dateStr)
            devices <- deviceRepo.listAll.orElseFail(Response.internalServerError(""))
            statuses <- ZIO.foreach(devices) { d =>
                          buildDeviceTimeStatus(d, date, profileRepo, timeLimitRepo,
                            siteTimeLimitRepo, usageRepo, extRepo)
                        }.orElseFail(Response.internalServerError(""))
          yield Response.json(statuses.toJson)
        },

      Method.GET / "api" / "time" / "status" / string("mac") ->
        handler { (mac: String, req: Request) =>
          for
            _       <- requireAuth(req, auth)
            dateStr  = req.url.queryParam("date").getOrElse(LocalDate.now().toString)
            date     = LocalDate.parse(dateStr)
            device  <- deviceRepo.findByMac(normalizeMac(mac))
                         .orElseFail(Response.internalServerError(""))
                         .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            status  <- buildDeviceTimeStatus(device, date, profileRepo, timeLimitRepo,
                         siteTimeLimitRepo, usageRepo, extRepo)
                         .orElseFail(Response.internalServerError(""))
          yield Response.json(status.toJson)
        },

      Method.POST / "api" / "time" / "extend" ->
        handler { (req: Request) =>
          for
            claims <- requireAdmin(req, auth)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            ger    <- ZIO.fromEither(body.fromJson[GrantExtensionRequest]).mapError(e => Response.badRequest(e))
            mac     = normalizeMac(ger.deviceMac)
            today   = LocalDate.now()
            id     <- extRepo.grant(mac, today, ger.extraMinutes, claims.sub, ger.note)
                        .orElseFail(Response.internalServerError(""))
          yield Response.json(s"""{"id":$id,"grantedMinutes":${ger.extraMinutes}}""")
        },

      Method.GET / "api" / "time" / "extensions" / string("mac") ->
        handler { (mac: String, req: Request) =>
          for
            _    <- requireAuth(req, auth)
            date  = LocalDate.now()
            exts <- extRepo.listForDevice(normalizeMac(mac), date)
                      .orElseFail(Response.internalServerError(""))
          yield Response.json(exts.toJson)
        },
    )

  private def buildDeviceTimeStatus(
    device:       Device,
    date:         LocalDate,
    profileRepo:  ProfileRepo,
    tlRepo:       TimeLimitRepo,
    stlRepo:      SiteTimeLimitRepo,
    usageRepo:    TimeUsageRepo,
    extRepo:      TimeExtensionRepo,
  ): Task[DeviceTimeStatus] =
    for
      tl       <- tlRepo.findForProfile(device.profileId)
      stls     <- stlRepo.listForProfile(device.profileId)
      usages   <- usageRepo.listForDevice(device.mac, date)
      extMins  <- extRepo.getTotalExtension(device.mac, date)
      profile  <- profileRepo.findById(device.profileId).map(_.map(_.name).getOrElse("Unknown"))
      totalUsed = usages.filterNot(u => stls.exists(s => matchesPattern(u.domain, s.domainPattern)))
                    .map(_.minutesUsed).sum
      remaining = tl.map(l => (l.dailyMinutes + extMins - totalUsed).max(0))
      siteUsage = stls.map { stl =>
                    val used = usages.filter(u => matchesPattern(u.domain, stl.domainPattern))
                                 .map(_.minutesUsed).sum
                    SiteUsage(stl.label, stl.domainPattern, stl.dailyMinutes, used, (stl.dailyMinutes - used).max(0))
                  }
    yield DeviceTimeStatus(
      device.mac, device.name, date.toString, profile,
      tl.map(_.dailyMinutes), totalUsed, extMins, remaining, siteUsage,
    )

  private def matchesPattern(domain: String, pattern: String): Boolean =
    if pattern.startsWith("*.") then domain.endsWith(pattern.drop(1)) || domain == pattern.drop(2)
    else domain == pattern || domain.endsWith(s".$pattern")

// ── Query log routes ───────────────────────────────────────────────────────

object LogRoutes:
  def routes(auth: AuthService, logRepo: QueryLogRepo): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "logs" ->
        handler { (req: Request) =>
          for
            _      <- requireAuth(req, auth)
            params  = req.url.queryParams
            filter  = LogFilter(
                        mac      = params.get("mac").flatMap(_.headOption),
                        blocked  = params.get("blocked").flatMap(_.headOption).map(_ == "true"),
                        domain   = params.get("domain").flatMap(_.headOption),
                        location = params.get("location").flatMap(_.headOption),
                        hours    = params.get("hours").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(24),
                        limit    = params.get("limit").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(200),
                        offset   = params.get("offset").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(0),
                      )
            logs   <- logRepo.query(filter).orElseFail(Response.internalServerError(""))
          yield Response.json(logs.toJson)
        },

      Method.GET / "api" / "stats" ->
        handler { (req: Request) =>
          requireAuth(req, auth) *>
          logRepo.stats.map(s => Response.json(s.toJson))
            .orElseFail(Response.internalServerError(""))
        },
    )

// ── Blocklist routes ───────────────────────────────────────────────────────

object BlocklistRoutes:
  def routes(auth: AuthService, blRepo: BlocklistRepo): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "blocklists" ->
        handler { (req: Request) =>
          requireAdmin(req, auth) *>
          blRepo.countByCategory.map(cs =>
            Response.json(cs.map((c,n) => s"""{"category":"$c","count":$n}""").mkString("[",",","]"))
          ).orElseFail(Response.internalServerError(""))
        },

      Method.POST / "api" / "blocklists" / string("category") / "clear" ->
        handler { (cat: String, req: Request) =>
          requireAdmin(req, auth) *>
          blRepo.clearCategory(cat).orElseFail(Response.internalServerError("")) *>
          ZIO.succeed(Response.ok)
        },
    )

// ── Helpers ────────────────────────────────────────────────────────────────

private def bearerToken(req: Request): Option[String] =
  req.header(Header.Authorization).flatMap { h =>
    val v = h.renderedValue
    if v.startsWith("Bearer ") then Some(v.drop(7)) else None
  }

def requireAuth(req: Request, auth: AuthService): IO[Response, JwtClaims] =
  ZIO.fromOption(bearerToken(req))
    .orElseFail(Response.unauthorized("Missing token"))
    .flatMap(t => auth.verify(t).mapError {
      case AuthError.TokenExpired => Response.unauthorized("Token expired")
      case _                     => Response.unauthorized("Invalid token")
    })

def requireAdmin(req: Request, auth: AuthService): IO[Response, JwtClaims] =
  ZIO.fromOption(bearerToken(req))
    .orElseFail(Response.unauthorized("Missing token"))
    .flatMap(t => auth.requireAdmin(t).mapError {
      case AuthError.Forbidden    => Response.forbidden("Admin required")
      case AuthError.TokenExpired => Response.unauthorized("Token expired")
      case _                     => Response.unauthorized("Invalid token")
    })

def normalizeMac(mac: String): String =
  mac.toLowerCase.replace("-", ":").trim
