import React, { useEffect, useState, useCallback } from 'react'
import { useParams, useLocation, useNavigate } from 'react-router-dom'
import { useSocket } from '../contexts/GameSocketContext'
import PlayerArea from '../components/PlayerArea'
import Stack from '../components/Stack'
import TurnIndicator from '../components/TurnIndicator'
import ActionModal from '../components/ActionModal'
import CommanderDamageMatrix from '../components/CommanderDamageMatrix'

// Determine local player from the game state's myPlayerId field (set by serializer)
function useLocalPlayerId(gameState) {
  const [localPlayerId, setLocalPlayerId] = useState(null)
  useEffect(() => {
    if (gameState?.myPlayerId && !localPlayerId) {
      setLocalPlayerId(gameState.myPlayerId)
    }
  }, [gameState?.myPlayerId, localPlayerId])
  return [localPlayerId, setLocalPlayerId]
}

export default function Game() {
  const { gameId } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const { playerName, format: stateFormat } = location.state ?? {}

  const { gameState, connected, waitingAction, gameOver, connect, disconnect, send } =
    useSocket()

  const format = gameState?.format ?? stateFormat ?? 'STANDARD'

  const isCommander = format === 'COMMANDER'

  const [localPlayerId, setLocalPlayerId] = useLocalPlayerId(gameState)
  const [selectedCard, setSelectedCard] = useState(null)
  const [targets, setTargets] = useState([])
  const [localLifeOverrides, setLocalLifeOverrides] = useState({})

  // Connection is managed by Lobby via shared context — no reconnect needed here.

  // --- Action handlers ---
  const handlePassPriority = useCallback(() => {
    send({ type: 'PASS_PRIORITY', payload: {} })
  }, [send])

  const handleCardClick = useCallback((card, playerId) => {
    if (!gameState) return

    const isMyTurn = gameState.priorityPlayerId === localPlayerId

    if (selectedCard?.id === card.id) {
      // Deselect
      setSelectedCard(null)
      setTargets([])
      return
    }

    if (selectedCard) {
      // Add as target
      setTargets((prev) => {
        const next = [...prev, card.id]
        // Auto-submit play once a target is chosen (simplistic)
        send({
          type: 'PLAY_CARD',
          payload: { cardId: selectedCard.id, targets: next },
        })
        setSelectedCard(null)
        return []
      })
      return
    }

    if (isMyTurn) {
      // Select the card to play or target
      setSelectedCard(card)
    }
  }, [gameState, localPlayerId, selectedCard, send])

  const handleHandCardClick = useCallback((card) => {
    const isMyTurn = gameState?.priorityPlayerId === localPlayerId
    if (!isMyTurn || !card?.id) return
    // PLAY_CARD sends the card UUID as the XMage UUID response (priority)
    send({ type: 'PLAY_CARD', data: card.id })
  }, [gameState, localPlayerId, send])

  const handleCommanderClick = useCallback((card) => {
    const isMyTurn = gameState?.priorityPlayerId === localPlayerId
    if (!isMyTurn || !card?.id) return
    send({ type: 'PLAY_CARD', data: card.id })
  }, [gameState, localPlayerId, send])

  const handleActionSubmit = useCallback((value) => {
    if (typeof value === 'boolean') {
      send({ type: 'SEND_BOOLEAN', data: value })
    } else if (typeof value === 'string') {
      // UUID or string choice
      send({ type: 'SEND_UUID', data: value })
    } else {
      send({ type: 'CHOOSE', data: value })
    }
  }, [send])

  const handleLifeChange = useCallback((playerId, delta) => {
    setLocalLifeOverrides((prev) => ({
      ...prev,
      [playerId]: (prev[playerId] ?? (gameState?.players.find(p => p.id === playerId)?.life ?? 0)) + delta,
    }))
  }, [gameState])

  // Players split: local at bottom, opponents at top
  const players = gameState?.players ?? []
  const localPlayer = players.find((p) => p.id === localPlayerId) ?? players[0]
  const opponents = players.filter((p) => p.id !== localPlayer?.id)

  const activePlayer = players.find((p) => p.id === gameState?.activePlayerId)

  // Apply local life overrides
  const applyLifeOverride = (player) => ({
    ...player,
    life: localLifeOverrides[player.id] ?? player.life,
  })

  return (
    <div className="min-h-screen bg-gray-950 flex flex-col overflow-hidden">
      {/* Top bar: connection + game ID */}
      <div className="flex items-center justify-between px-4 py-1.5 bg-gray-900 border-b border-gray-800 shrink-0">
        <div className="flex items-center gap-2">
          <div className={`w-2 h-2 rounded-full ${connected ? 'bg-green-400' : 'bg-red-500'}`} />
          <span className="text-gray-500 text-xs">{connected ? 'Connected' : 'Disconnected'}</span>
          <span className="text-gray-700 text-xs ml-2">Game: {gameId}</span>
        </div>
        <button
          onClick={() => { disconnect(); navigate('/') }}
          className="text-gray-600 hover:text-gray-400 text-xs"
        >
          Leave Game
        </button>
      </div>

      {/* Main layout */}
      <div className="flex flex-1 overflow-hidden">
        {/* Game area */}
        <div className="flex-1 flex flex-col overflow-y-auto p-2 gap-2">
          {/* Opponents */}
          {opponents.length > 0 ? (
            opponents.map((opp) => (
              <PlayerArea
                key={opp.id}
                player={applyLifeOverride(opp)}
                isOpponent={true}
                format={format}
                isActive={opp.id === gameState?.activePlayerId}
                hasPriority={opp.id === gameState?.priorityPlayerId}
                onCardClick={(card) => handleCardClick(card, opp.id)}
                onLifeChange={handleLifeChange}
                targetableCardIds={
                  selectedCard
                    ? new Set(opp.battlefield?.map((c) => c.id) ?? [])
                    : new Set()
                }
                selectedCardId={selectedCard?.id}
                localPlayerId={localPlayerId}
              />
            ))
          ) : (
            <div className="flex-1 flex items-center justify-center">
              <p className="text-gray-700 text-sm italic">Waiting for opponent...</p>
            </div>
          )}

          {/* Turn Indicator (center) */}
          <div className="flex justify-center py-1 shrink-0">
            <TurnIndicator
              turn={gameState?.turn}
              phase={gameState?.phase}
              activePlayer={activePlayer}
              priorityPlayerId={gameState?.priorityPlayerId}
              localPlayerId={localPlayerId}
              onPassPriority={handlePassPriority}
              connected={connected}
            />
          </div>

          {/* Local player */}
          {localPlayer && (
            <PlayerArea
              player={applyLifeOverride(localPlayer)}
              isOpponent={false}
              format={format}
              isActive={localPlayer.id === gameState?.activePlayerId}
              hasPriority={localPlayer.id === gameState?.priorityPlayerId}
              onCardClick={handleHandCardClick}
              onCommanderClick={handleCommanderClick}
              onLifeChange={handleLifeChange}
              playableCardIds={new Set(gameState?.canPlayIds ?? [])}
              selectedCardId={selectedCard?.id}
              localPlayerId={localPlayerId}
            />
          )}
        </div>

        {/* Right sidebar: Stack + Commander damage */}
        {(gameState?.stack?.length > 0 || isCommander) && (
          <div className="w-64 flex flex-col gap-3 p-2 border-l border-gray-800 bg-gray-900/50 overflow-y-auto shrink-0">
            {gameState?.stack?.length > 0 && (
              <Stack stack={gameState.stack} players={players} />
            )}
            {isCommander && players.length > 0 && (
              <CommanderDamageMatrix players={players} />
            )}
          </div>
        )}
      </div>

      {/* Action modal */}
      <ActionModal
        waitingAction={waitingAction}
        onSubmit={handleActionSubmit}
        onClose={() => send({ type: 'PASS_PRIORITY' })}
      />

      {/* Game over overlay */}
      {gameOver && (
        <div className="fixed inset-0 bg-black/80 z-[200] flex items-center justify-center">
          <div className="bg-gray-900 border border-yellow-700 rounded-2xl p-8 text-center max-w-sm w-full shadow-2xl">
            <h2 className="text-3xl font-bold text-yellow-400 mb-2">Game Over</h2>
            <p className="text-gray-300 text-lg mb-1">
              Winner: <span className="text-yellow-300 font-semibold">{gameOver.winner ?? 'Unknown'}</span>
            </p>
            {gameOver.reason && (
              <p className="text-gray-500 text-sm mb-4">{gameOver.reason}</p>
            )}
            <button
              onClick={() => { disconnect(); navigate('/') }}
              className="mt-2 px-6 py-2 bg-yellow-600 hover:bg-yellow-500 text-black font-bold rounded-lg"
            >
              Back to Lobby
            </button>
          </div>
        </div>
      )}

      {/* No game state yet */}
      {!gameState && !gameOver && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center">
            <div className="w-8 h-8 border-2 border-yellow-600 border-t-transparent rounded-full animate-spin mx-auto mb-3" />
            <p className="text-gray-600 text-sm">Waiting for game state...</p>
          </div>
        </div>
      )}
    </div>
  )
}
