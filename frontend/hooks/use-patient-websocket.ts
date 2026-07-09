import { useEffect, useRef, useState } from 'react'
import type { QueryClient } from '@tanstack/react-query'
import { measurementsKey } from '../lib/query-keys'
import { mergeMeasurement } from '../lib/merge-measurement'
import type { ConnectionState } from '../lib/connection-state'
import type { MeasurementPage, WsMessage } from '../lib/types'
import { WS_BASE_URL } from '../lib/config'

const WS_BASE = WS_BASE_URL

function reconnectDelay(attempt: number): number {
  const delays = [1_000, 2_000, 4_000, 8_000, 16_000]
  return delays[Math.min(attempt, delays.length - 1)] ?? 30_000
}

export function usePatientWebSocket(patientId: string, queryClient: QueryClient) {
  const [connectionState, setConnectionState] = useState<ConnectionState>('disconnected')
  const attemptRef = useRef(0)
  const socketRef = useRef<WebSocket | null>(null)
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const mountedRef = useRef(true)

  useEffect(() => {
    mountedRef.current = true

    function connect() {
      setConnectionState('reconnecting')
      const socket = new WebSocket(`${WS_BASE}/patients/${patientId}`)
      socketRef.current = socket

      socket.onopen = () => {
        attemptRef.current = 0
        setConnectionState('connected')
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

  return { connectionState }
}
