import React, { createContext, useContext, useState, useCallback } from 'react'
import { api } from '@/api/client'

interface AuthState {
  token: string | null
  username: string | null
  role: string | null
}

interface AuthContextValue extends AuthState {
  login: (username: string, password: string) => Promise<void>
  logout: () => void
  isAdmin: boolean
  isAuthenticated: boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>(() => ({
    token:    localStorage.getItem('token'),
    username: localStorage.getItem('username'),
    role:     localStorage.getItem('role'),
  }))

  const login = useCallback(async (username: string, password: string) => {
    const resp = await api.auth.login(username, password)
    localStorage.setItem('token', resp.token)
    localStorage.setItem('username', resp.username)
    localStorage.setItem('role', resp.role)
    setState({ token: resp.token, username: resp.username, role: resp.role })
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('role')
    setState({ token: null, username: null, role: null })
  }, [])

  return (
    <AuthContext.Provider value={{
      ...state,
      login,
      logout,
      isAdmin:         state.role === 'admin',
      isAuthenticated: !!state.token,
    }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
