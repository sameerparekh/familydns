import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { useAuth } from '@/hooks/useAuth'
import type { DeviceTimeStatus } from '@/types/api'
import { PageLoader } from './DashboardPage'

export function TimePage() {
  const { isAdmin }   = useAuth()
  const [statuses, setStatuses] = useState<DeviceTimeStatus[]>([])
  const [loading, setLoading]   = useState(true)
  const [extMac, setExtMac]     = useState<string | null>(null)
  const [extMins, setExtMins]   = useState(30)
  const [extNote, setExtNote]   = useState('')
  const [granting, setGranting] = useState(false)

  async function load() {
    setLoading(true)
    try {
      const data = await api.time.statusAll()
      setStatuses(data)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  async function grantExtension(mac: string) {
    setGranting(true)
    try {
      await api.time.grantExtension({ deviceMac: mac, extraMinutes: extMins, note: extNote || null })
      setExtMac(null)
      setExtMins(30)
      setExtNote('')
      await load()
    } finally {
      setGranting(false)
    }
  }

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-white">Screen Time</h1>
        <span className="text-xs text-gray-500 font-mono">{new Date().toLocaleDateString()}</span>
      </div>

      {statuses.length === 0 && (
        <p className="text-gray-500 text-sm">No devices found. Add devices first.</p>
      )}

      <div className="grid gap-4 md:grid-cols-2">
        {statuses.map(s => (
          <DeviceTimeCard
            key={s.deviceMac}
            status={s}
            isAdmin={isAdmin}
            onGrant={() => setExtMac(s.deviceMac)}
          />
        ))}
      </div>

      {/* Extension modal */}
      {extMac && (
        <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
          <div className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-sm p-6 space-y-4">
            <h3 className="text-lg font-bold text-white">Grant Extra Time</h3>
            <p className="text-sm text-gray-400 font-mono">{extMac}</p>

            <div>
              <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
                Extra minutes
              </label>
              <div className="flex gap-2">
                {[15, 30, 45, 60].map(m => (
                  <button
                    key={m}
                    onClick={() => setExtMins(m)}
                    className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
                      extMins === m
                        ? 'bg-emerald-500 text-black'
                        : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                    }`}
                  >
                    {m}m
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
                Note (optional)
              </label>
              <input
                type="text"
                value={extNote}
                onChange={e => setExtNote(e.target.value)}
                placeholder="Homework finished, good behavior…"
                className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white text-sm placeholder-gray-600 focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div className="flex gap-3 pt-2">
              <button
                onClick={() => setExtMac(null)}
                className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium hover:bg-gray-700 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => grantExtension(extMac)}
                disabled={granting}
                className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold hover:bg-emerald-400 disabled:opacity-50 transition-colors"
              >
                {granting ? 'Granting…' : `Grant ${extMins}m`}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function DeviceTimeCard({
  status, isAdmin, onGrant,
}: {
  status: DeviceTimeStatus
  isAdmin: boolean
  onGrant: () => void
}) {
  const hasLimit = status.dailyLimitMins !== null
  const pct = hasLimit && status.dailyLimitMins
    ? Math.min(100, Math.round((status.usedMins / (status.dailyLimitMins + status.extensionMins)) * 100))
    : 0
  const overLimit = hasLimit && status.remainingMins !== null && status.remainingMins <= 0

  return (
    <div className={`bg-gray-900 rounded-2xl border p-5 space-y-4 ${overLimit ? 'border-red-500/40' : 'border-gray-800'}`}>
      <div className="flex items-start justify-between gap-2">
        <div>
          <h3 className="font-semibold text-white">{status.deviceName}</h3>
          <p className="text-xs text-gray-500 font-mono">{status.deviceMac}</p>
          <p className="text-xs text-gray-600 mt-0.5">{status.profileName}</p>
        </div>
        {isAdmin && hasLimit && (
          <button
            onClick={onGrant}
            className="shrink-0 text-xs bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border border-emerald-500/20 px-3 py-1.5 rounded-lg transition-colors"
          >
            + Time
          </button>
        )}
      </div>

      {hasLimit ? (
        <>
          <div>
            <div className="flex justify-between text-xs text-gray-500 mb-1.5">
              <span>{status.usedMins}m used</span>
              <span>
                {status.remainingMins !== null && status.remainingMins > 0
                  ? `${status.remainingMins}m left`
                  : <span className="text-red-400">Limit reached</span>
                }
              </span>
            </div>
            <div className="h-2 bg-gray-800 rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-all ${overLimit ? 'bg-red-500' : 'bg-emerald-500'}`}
                style={{ width: `${pct}%` }}
              />
            </div>
            <div className="flex justify-between text-xs text-gray-600 mt-1">
              <span>Limit: {status.dailyLimitMins}m</span>
              {status.extensionMins > 0 && (
                <span className="text-yellow-500">+{status.extensionMins}m extended</span>
              )}
            </div>
          </div>

          {status.siteUsage.length > 0 && (
            <div className="space-y-2">
              <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider">Site Limits</p>
              {status.siteUsage.map(su => (
                <div key={su.domainPattern} className="bg-gray-800/50 rounded-xl p-3">
                  <div className="flex justify-between text-xs mb-1">
                    <span className="text-gray-300 font-medium">{su.label}</span>
                    <span className={su.remainingMins <= 0 ? 'text-red-400' : 'text-gray-500'}>
                      {su.remainingMins <= 0 ? 'Limit reached' : `${su.remainingMins}m left`}
                    </span>
                  </div>
                  <div className="h-1.5 bg-gray-700 rounded-full overflow-hidden">
                    <div
                      className={`h-full rounded-full ${su.remainingMins <= 0 ? 'bg-red-500' : 'bg-yellow-500'}`}
                      style={{ width: `${Math.min(100, Math.round((su.usedMins / su.limitMins) * 100))}%` }}
                    />
                  </div>
                  <p className="text-xs text-gray-600 mt-1 font-mono">{su.domainPattern}</p>
                </div>
              ))}
            </div>
          )}
        </>
      ) : (
        <p className="text-xs text-gray-600">No time limit set for this profile</p>
      )}
    </div>
  )
}
