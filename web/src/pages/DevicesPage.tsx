import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { useAuth } from '@/hooks/useAuth'
import type { Device, ProfileDetail } from '@/types/api'
import { PageLoader } from './DashboardPage'

// ── Devices page ───────────────────────────────────────────────────────────

export function DevicesPage() {
  const { isAdmin } = useAuth()
  const [devices,  setDevices]  = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])
  const [loading,  setLoading]  = useState(true)
  const [editing,  setEditing]  = useState<Device | null>(null)
  const [form,     setForm]     = useState({ mac: '', name: '', profileId: 0, location: 'home' })

  useEffect(() => {
    Promise.all([api.devices.list(), api.profiles.list()])
      .then(([d, p]) => { setDevices(d); setProfiles(p) })
      .finally(() => setLoading(false))
  }, [])

  async function save() {
    await api.devices.upsert({ ...form, location: form.location || null })
    const d = await api.devices.list()
    setDevices(d)
    setEditing(null)
  }

  async function del(mac: string) {
    if (!confirm('Remove this device?')) return
    await api.devices.delete(mac)
    setDevices(d => d.filter(x => x.mac !== mac))
  }

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-white">Devices</h1>
        {isAdmin && (
          <button
            onClick={() => { setEditing({} as Device); setForm({ mac: '', name: '', profileId: profiles[0]?.profile.id ?? 0, location: 'home' }) }}
            className="bg-emerald-500 hover:bg-emerald-400 text-black text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            + Add Device
          </button>
        )}
      </div>

      <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
        {devices.length === 0
          ? <p className="p-6 text-gray-500 text-sm">No devices yet.</p>
          : devices.map(d => (
              <div key={d.mac} className="flex items-center gap-4 px-5 py-4 border-b border-gray-800 last:border-0">
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-white truncate">{d.name}</p>
                  <p className="text-xs text-gray-500 font-mono">{d.mac}</p>
                </div>
                <div className="hidden sm:block text-sm">
                  <span className="bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 px-2 py-1 rounded-lg text-xs">
                    {d.profileName ?? 'No profile'}
                  </span>
                </div>
                <div className="text-xs text-gray-600 hidden md:block">{d.location ?? 'home'}</div>
                {isAdmin && (
                  <div className="flex gap-2 shrink-0">
                    <button
                      onClick={() => { setEditing(d); setForm({ mac: d.mac, name: d.name, profileId: d.profileId, location: d.location ?? 'home' }) }}
                      className="text-xs text-gray-400 hover:text-white bg-gray-800 px-3 py-1.5 rounded-lg transition-colors"
                    >Edit</button>
                    <button
                      onClick={() => del(d.mac)}
                      className="text-xs text-red-400 hover:text-red-300 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors"
                    >Remove</button>
                  </div>
                )}
              </div>
            ))
        }
      </div>

      {editing && (
        <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
          <div className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-sm p-6 space-y-4">
            <h3 className="text-lg font-bold text-white">{form.mac ? 'Edit Device' : 'Add Device'}</h3>
            <Field label="MAC Address" value={form.mac} onChange={v => setForm(f => ({...f, mac: v}))} placeholder="aa:bb:cc:dd:ee:ff" mono />
            <Field label="Name" value={form.name} onChange={v => setForm(f => ({...f, name: v}))} placeholder="Kid's iPad" />
            <div>
              <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Profile</label>
              <select value={form.profileId} onChange={e => setForm(f => ({...f, profileId: Number(e.target.value)}))}
                className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white">
                {profiles.map(p => <option key={p.profile.id} value={p.profile.id}>{p.profile.name}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Location</label>
              <select value={form.location} onChange={e => setForm(f => ({...f, location: e.target.value}))}
                className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white">
                <option value="home">Home</option>
                <option value="vacation">Vacation</option>
              </select>
            </div>
            <div className="flex gap-3 pt-2">
              <button onClick={() => setEditing(null)} className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium">Cancel</button>
              <button onClick={save} className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold">Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ── Profiles page ──────────────────────────────────────────────────────────

export function ProfilesPage() {
  const { isAdmin } = useAuth()
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])
  const [loading,  setLoading]  = useState(true)
  const [categories, setCategories] = useState<string[]>([])
  const [adding,   setAdding]   = useState(false)
  const [form,     setForm]     = useState<{ name: string; blockedCategories: string[]; dailyMinutes: string }>({ name: '', blockedCategories: [], dailyMinutes: '' })
  const [saving,   setSaving]   = useState(false)
  const [error,    setError]    = useState<string | null>(null)

  async function refresh() {
    const p = await api.profiles.list()
    setProfiles(p)
  }

  useEffect(() => {
    Promise.all([
      api.profiles.list(),
      api.blocklists.counts().catch(() => [] as { category: string; count: number }[]),
    ])
      .then(([p, c]) => { setProfiles(p); setCategories(c.map(x => x.category)) })
      .finally(() => setLoading(false))
  }, [])

  async function togglePause(id: number) {
    await api.profiles.pause(id)
    await refresh()
  }

  function openAdd() {
    setForm({ name: '', blockedCategories: [], dailyMinutes: '' })
    setError(null)
    setAdding(true)
  }

  async function saveNew() {
    if (!form.name.trim()) { setError('Name is required'); return }
    setSaving(true)
    setError(null)
    try {
      const minutes = form.dailyMinutes.trim()
      await api.profiles.create({
        name: form.name.trim(),
        blockedCategories: form.blockedCategories,
        extraBlocked: [],
        extraAllowed: [],
        paused: false,
        schedules: [],
        timeLimit: minutes ? Number(minutes) : null,
        siteTimeLimits: [],
      })
      setAdding(false)
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  function toggleCategory(c: string) {
    setForm(f => ({
      ...f,
      blockedCategories: f.blockedCategories.includes(c)
        ? f.blockedCategories.filter(x => x !== c)
        : [...f.blockedCategories, c],
    }))
  }

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-white">Profiles</h1>
        {isAdmin && (
          <button
            onClick={openAdd}
            className="bg-emerald-500 hover:bg-emerald-400 text-black text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            + Add Profile
          </button>
        )}
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        {profiles.map(pd => (
          <div key={pd.profile.id} className="bg-gray-900 rounded-2xl border border-gray-800 p-5 space-y-4">
            <div className="flex items-start justify-between gap-2">
              <div>
                <h3 className="font-bold text-white text-lg">{pd.profile.name}</h3>
                {pd.profile.paused && (
                  <span className="text-xs text-red-400 bg-red-500/10 px-2 py-0.5 rounded mt-1 inline-block">Paused</span>
                )}
              </div>
              {isAdmin && (
                <button onClick={() => togglePause(pd.profile.id)}
                  className={`shrink-0 text-xs px-3 py-1.5 rounded-lg border transition-colors ${
                    pd.profile.paused
                      ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20 hover:bg-emerald-500/20'
                      : 'bg-yellow-500/10 text-yellow-400 border-yellow-500/20 hover:bg-yellow-500/20'
                  }`}>
                  {pd.profile.paused ? '▶ Resume' : '⏸ Pause'}
                </button>
              )}
            </div>

            {pd.profile.blockedCategories.length > 0 && (
              <div>
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Blocked categories</p>
                <div className="flex flex-wrap gap-2">
                  {pd.profile.blockedCategories.map(c => (
                    <span key={c} className="text-xs bg-red-500/10 text-red-400 px-2 py-1 rounded-lg font-mono">{c}</span>
                  ))}
                </div>
              </div>
            )}

            {pd.timeLimit && (
              <p className="text-sm text-gray-400">
                Daily limit: <span className="text-white font-medium">{pd.timeLimit.dailyMinutes} min</span>
              </p>
            )}

            {pd.schedules.length > 0 && (
              <div>
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Schedules</p>
                {pd.schedules.map(s => (
                  <div key={s.id} className="flex justify-between text-sm bg-gray-800/50 rounded-lg px-3 py-2 mb-1">
                    <span className="text-gray-300">{s.name}</span>
                    <span className="text-yellow-400 font-mono text-xs">{s.blockFrom} → {s.blockUntil}</span>
                  </div>
                ))}
              </div>
            )}

            {pd.siteTimeLimits.length > 0 && (
              <div>
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Site Limits</p>
                {pd.siteTimeLimits.map(s => (
                  <div key={s.id} className="flex justify-between text-sm bg-gray-800/50 rounded-lg px-3 py-2 mb-1">
                    <span className="text-gray-300">{s.label}</span>
                    <span className="text-emerald-400 text-xs font-mono">{s.dailyMinutes}m · {s.domainPattern}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      {adding && (
        <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
          <div className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-sm p-6 space-y-4">
            <h3 className="text-lg font-bold text-white">Add Profile</h3>
            <Field label="Name" value={form.name} onChange={v => setForm(f => ({...f, name: v}))} placeholder="Kids" />
            <Field label="Daily Limit (minutes, optional)" value={form.dailyMinutes} onChange={v => setForm(f => ({...f, dailyMinutes: v.replace(/[^0-9]/g, '')}))} placeholder="e.g. 120" />
            {categories.length > 0 && (
              <div>
                <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Blocked Categories</label>
                <div className="flex flex-wrap gap-2">
                  {categories.map(c => {
                    const on = form.blockedCategories.includes(c)
                    return (
                      <button
                        key={c}
                        type="button"
                        onClick={() => toggleCategory(c)}
                        className={`text-xs px-2 py-1 rounded-lg font-mono border transition-colors ${
                          on
                            ? 'bg-red-500/20 text-red-300 border-red-500/40'
                            : 'bg-gray-800 text-gray-400 border-gray-700 hover:bg-gray-700'
                        }`}
                      >{c}</button>
                    )
                  })}
                </div>
              </div>
            )}
            {error && <p className="text-sm text-red-400">{error}</p>}
            <div className="flex gap-3 pt-2">
              <button onClick={() => setAdding(false)} disabled={saving} className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium disabled:opacity-50">Cancel</button>
              <button onClick={saveNew} disabled={saving} className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold disabled:opacity-50">
                {saving ? 'Saving…' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ── Logs page ──────────────────────────────────────────────────────────────

export function LogsPage() {
  const [logs,     setLogs]     = useState<import('@/types/api').QueryLog[]>([])
  const [loading,  setLoading]  = useState(true)
  const [domain,   setDomain]   = useState('')
  const [blocked,  setBlocked]  = useState<'all' | 'true' | 'false'>('all')

  async function load() {
    setLoading(true)
    try {
      const data = await api.logs.query({
        domain:  domain || undefined,
        blocked: blocked === 'all' ? undefined : blocked === 'true',
        limit:   200,
      })
      setLogs(data)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [domain, blocked])

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-white">Query Log</h1>

      <div className="flex flex-wrap gap-3">
        <input
          type="text"
          value={domain}
          onChange={e => setDomain(e.target.value)}
          placeholder="Filter by domain…"
          className="bg-gray-900 border border-gray-700 rounded-xl px-4 py-2.5 text-white text-sm font-mono placeholder-gray-600 focus:outline-none focus:border-emerald-500 flex-1 min-w-[160px]"
        />
        <select value={blocked} onChange={e => setBlocked(e.target.value as typeof blocked)}
          className="bg-gray-900 border border-gray-700 rounded-xl px-4 py-2.5 text-white text-sm">
          <option value="all">All queries</option>
          <option value="true">Blocked only</option>
          <option value="false">Allowed only</option>
        </select>
        <button onClick={load} className="bg-gray-800 hover:bg-gray-700 text-white text-sm px-4 py-2.5 rounded-xl transition-colors">
          Refresh
        </button>
      </div>

      <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
        {loading
          ? <div className="p-8 flex justify-center"><div className="w-6 h-6 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin"/></div>
          : <div className="overflow-x-auto">
              <table className="w-full text-xs font-mono">
                <thead>
                  <tr className="text-gray-600 border-b border-gray-800">
                    <th className="text-left px-4 py-3">Time</th>
                    <th className="text-left px-4 py-3">Device</th>
                    <th className="text-left px-4 py-3">Domain</th>
                    <th className="text-left px-4 py-3">Status</th>
                    <th className="text-left px-4 py-3 hidden md:table-cell">Reason</th>
                    <th className="text-left px-4 py-3 hidden lg:table-cell">Location</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map(l => (
                    <tr key={l.id} className="border-b border-gray-800/50 hover:bg-gray-800/30">
                      <td className="px-4 py-2.5 text-gray-500">{l.ts.slice(11,19)}</td>
                      <td className="px-4 py-2.5 text-yellow-400">{l.deviceName ?? l.mac ?? '?'}</td>
                      <td className="px-4 py-2.5 text-gray-300 max-w-[200px] truncate">{l.domain}</td>
                      <td className={`px-4 py-2.5 ${l.blocked ? 'text-red-400' : 'text-emerald-600'}`}>
                        {l.blocked ? '✗ blocked' : '✓ ok'}
                      </td>
                      <td className="px-4 py-2.5 text-gray-600 hidden md:table-cell">{l.reason}</td>
                      <td className="px-4 py-2.5 text-gray-600 hidden lg:table-cell">{l.location ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {logs.length === 0 && <p className="p-6 text-gray-500 text-sm">No logs found.</p>}
            </div>
        }
      </div>
    </div>
  )
}

// ── Shared helpers ─────────────────────────────────────────────────────────

function Field({ label, value, onChange, placeholder, mono = false }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string; mono?: boolean
}) {
  return (
    <div>
      <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">{label}</label>
      <input type="text" value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        className={`w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white placeholder-gray-600 focus:outline-none focus:border-emerald-500 ${mono ? 'font-mono text-sm' : ''}`} />
    </div>
  )
}
