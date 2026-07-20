import { useEffect, useRef, useState } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { measurementsKey, patientKey } from '../lib/query-keys'
import { mergeMeasurement } from '../lib/merge-measurement'
import type { ConnectionState } from '../lib/connection-state'
import type { MeasurementPage, Patient, WsMessage } from '../lib/types'
import { WS_BASE_URL } from '../lib/config'

const WS_BASE = WS_BASE_URL

function reconnectDelay(attempt: number): number {
  const delays = [1_000, 2_000, 4_000, 8_000, 16_000]
  return delays[Math.min(attempt, delays.length - 1)] ?? 30_000
}

export function usePatientWebSocket(patientId: string, queryClient: QueryClient) {
  const [connectionState, setConnectionState] = useState<ConnectionState>('disconnected')
  const [justDeactivated, setJustDeactivated] = useState(false)
  const attemptRef = useRef(0)
  const socketRef = useRef<WebSocket | null>(null)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true
    setJustDeactivated(false)

    function connect() {
      setConnectionState('reconnecting')
      const socket = new WebSocket(`${WS_BASE}/patients/${patientId}`)
      socketRef.current = socket

      socket.onopen = () => {
        attemptRef.current = 0
        setConnectionState('connected')
        queryClient.invalidateQueries({ queryKey: measurementsKey(patientId) })
      }

      socket.onmessage = (event) => {
        let message: WsMessage
        try {
          message = JSON.parse(event.data) as WsMessage
        } catch {
          return
        }

        if (message.type === 'ping') {
          socket.send(JSON.stringify({ type: 'pong', ts: message.ts }))
          return
        }

        if (message.type === 'measurement.created') {
          queryClient.setQueryData<MeasurementPage>(
            measurementsKey(patientId),
            (prev) => {
              const items = mergeMeasurement(
                (prev as MeasurementPage)?.items ?? [],
                message.data,
              )
              return { items, next_cursor: null }
            },
          )
          return
        }

        if (message.type === 'patient.deactivated') {
          // Mark the patient inactive in the cache immediately so the
          // UI does not present clearly-stale live data as live. The
          // next patient fetch will reconcile with the server truth.
          // Only surface the "just deactivated" hint when the cached
          // patient was active in this hook session — patients that
          // were already inactive before the page loaded must not
          // show a false live-deactivation cue.
          const wasActive =
            queryClient.getQueryData<Patient>(patientKey(patientId))?.is_active === true
          if (wasActive) setJustDeactivated(true)
          queryClient.setQueryData<Patient | undefined>(
            patientKey(patientId),
            (prev) => (prev ? { ...prev, is_active: false } : prev),
          )
        }
      }

      socket.onclose = () => {
        if (!mountedRef.current) return
        setConnectionState('reconnecting')
        const delay = reconnectDelay(attemptRef.current)
        attemptRef.current += 1
        reconnectTimerRef.current = setTimeout(connect, delay)
      }

      socket.onerror = () => {
        socket.close()
      }
    }

    connect()

    return () => {
      mountedRef.current = false
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current)
        reconnectTimerRef.current = null
      }
      socketRef.current?.close()
      socketRef.current = null
    }
  }, [patientId, queryClient])

  return { connectionState, justDeactivated }
}
