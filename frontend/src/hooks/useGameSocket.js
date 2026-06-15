import { useState, useRef, useCallback, useEffect } from 'react'

const MAX_RECONNECT_ATTEMPTS = 3
const RECONNECT_DELAY_MS = 2000

export function useGameSocket() {
  const [gameState, setGameState] = useState(null)
  const [connected, setConnected] = useState(false)
  const [waitingAction, setWaitingAction] = useState(null)
  const [gameOver, setGameOver] = useState(null)
  const [userId, setUserId] = useState(null)

  const wsRef = useRef(null)
  const gameIdRef = useRef(null)
  const reconnectAttemptsRef = useRef(0)
  const reconnectTimerRef = useRef(null)
  const manualDisconnectRef = useRef(false)

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current)
      reconnectTimerRef.current = null
    }
  }, [])

  // connect(gameId, initialMessage, onGameCreated)
  //   gameId: null for CREATE_GAME (server assigns), or known id for JOIN/reconnect
  //   initialMessage: JSON to send on open (e.g. CREATE_GAME or JOIN_GAME)
  //   onGameCreated: called with the real gameId once GAME_CREATED is received
  const connect = useCallback((gameId, initialMessage, onGameCreated) => {
    if (wsRef.current) {
      manualDisconnectRef.current = true
      wsRef.current.close()
    }

    gameIdRef.current = gameId
    manualDisconnectRef.current = false
    reconnectAttemptsRef.current = 0

    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const isLocalhost = window.location.hostname === 'localhost'
    const wsHost = isLocalhost
      ? window.location.host
      : `${window.location.hostname}:17172`
    // Use a stable path; gameId in URL is cosmetic for the WS bridge
    const urlGameId = gameId || 'new'
    const url = `${protocol}://${wsHost}/ws/game/${urlGameId}`

    const ws = new WebSocket(url)
    wsRef.current = ws

    ws.onopen = () => {
      setConnected(true)
      reconnectAttemptsRef.current = 0
      clearReconnectTimer()
      if (initialMessage) {
        ws.send(JSON.stringify(initialMessage))
      }
    }

    ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data)
        switch (message.type) {
          case 'GAME_CREATED':
            gameIdRef.current = message.gameId
            if (message.userId) setUserId(message.userId)
            if (onGameCreated) onGameCreated(message.gameId)
            break
          case 'JOINED_GAME':
            gameIdRef.current = message.gameId
            if (message.userId) setUserId(message.userId)
            if (onGameCreated) onGameCreated(message.gameId)
            break
          case 'GAME_STATE':
            setGameState(message.payload)
            // pendingQuery in the payload supersedes any separate WAITING_FOR_INPUT
            if (message.payload?.pendingQuery) {
              setWaitingAction(message.payload.pendingQuery)
            } else {
              setWaitingAction(null)
            }
            break
          case 'WAITING_FOR_INPUT':
            setWaitingAction({
              queryType: message.queryType,
              message: message.message,
              validTargets: message.validTargets ?? [],
            })
            break
          case 'PRIORITY':
            setGameState((prev) =>
              prev ? { ...prev, priorityPlayerId: message.payload.playerId } : prev
            )
            break
          case 'WAITING_ACTION':
            setWaitingAction(message.payload)
            break
          case 'GAME_OVER':
            setGameOver(message.payload)
            setWaitingAction(null)
            break
          case 'ERROR':
            console.error('[useGameSocket] Server error:', message.message)
            break
          default:
            console.warn('[useGameSocket] Unknown message type:', message.type)
        }
      } catch (err) {
        console.error('[useGameSocket] Failed to parse message:', err)
      }
    }

    ws.onclose = () => {
      setConnected(false)
      wsRef.current = null

      if (!manualDisconnectRef.current && reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
        reconnectAttemptsRef.current += 1
        console.log(`[useGameSocket] Reconnecting (attempt ${reconnectAttemptsRef.current}/${MAX_RECONNECT_ATTEMPTS})...`)
        reconnectTimerRef.current = setTimeout(() => {
          connect(gameIdRef.current)
        }, RECONNECT_DELAY_MS)
      }
    }

    ws.onerror = (err) => {
      console.error('[useGameSocket] WebSocket error:', err)
    }
  }, [clearReconnectTimer])

  const disconnect = useCallback(() => {
    manualDisconnectRef.current = true
    clearReconnectTimer()
    if (wsRef.current) {
      wsRef.current.close()
      wsRef.current = null
    }
    setConnected(false)
    setGameState(null)
    setWaitingAction(null)
    setGameOver(null)
    gameIdRef.current = null
    reconnectAttemptsRef.current = 0
  }, [clearReconnectTimer])

  const send = useCallback((message) => {
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(message))
    } else {
      console.warn('[useGameSocket] Cannot send — socket not open')
    }
  }, [])

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      manualDisconnectRef.current = true
      clearReconnectTimer()
      if (wsRef.current) {
        wsRef.current.close()
      }
    }
  }, [clearReconnectTimer])

  return {
    gameState,
    connected,
    waitingAction,
    gameOver,
    userId,
    connect,
    disconnect,
    send,
  }
}
