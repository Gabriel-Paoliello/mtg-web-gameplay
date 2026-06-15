import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useSocket } from '../contexts/GameSocketContext'

const FORMAT_INFO = {
  STANDARD: {
    label: 'Standard',
    description: 'Constructed format using the most recent sets. 60-card minimum deck, best-of-3.',
  },
  COMMANDER: {
    label: 'Commander',
    description: '100-card singleton deck built around a legendary commander. Multiplayer, starts at 40 life.',
  },
  PAUPER: {
    label: 'Pauper',
    description: '60-card minimum deck using only commons. Competitive and budget-friendly.',
  },
}

function generateGameId() {
  return Math.random().toString(36).slice(2, 8).toUpperCase()
}

export default function Lobby() {
  const navigate = useNavigate()
  const { connect } = useSocket()

  const [playerName, setPlayerName] = useState('')
  const [format, setFormat] = useState('STANDARD')
  const [deckList, setDeckList] = useState('')
  const [commander, setCommander] = useState('')
  const [createdGameId, setCreatedGameId] = useState(null)
  const [joinCode, setJoinCode] = useState('')
  const [error, setError] = useState('')
  const [tab, setTab] = useState('create') // 'create' | 'join'

  const validateDeck = () => {
    const lines = deckList.trim().split('\n').filter(Boolean)
    if (lines.length === 0) return 'Deck list cannot be empty.'
    for (const line of lines) {
      if (!/^\d+\s+.+$/.test(line.trim())) {
        return `Invalid line: "${line}" — expected format: "4 Lightning Bolt"`
      }
    }
    if (format === 'COMMANDER' && !commander.trim()) {
      return 'Commander name is required for Commander format.'
    }
    return null
  }

  const handleCreate = () => {
    setError('')
    if (!playerName.trim()) { setError('Enter your player name.'); return }
    const deckError = validateDeck()
    if (deckError) { setError(deckError); return }

    const createMsg = {
      type: 'CREATE_GAME',
      format,
      playerName: playerName.trim(),
      deckList: deckList.trim(),
      ...(format === 'COMMANDER' && { commander: commander.trim() }),
    }

    // Connect then send CREATE_GAME; server returns GAME_CREATED with real gameId
    connect(null, createMsg, (realGameId) => {
      setCreatedGameId(realGameId)
      navigate(`/game/${realGameId}`, { state: { playerName, format } })
    })
  }

  const handleJoin = () => {
    setError('')
    if (!playerName.trim()) { setError('Enter your player name.'); return }
    const code = joinCode.trim()
    if (!code) { setError('Enter a game code.'); return }
    const deckError = validateDeck()
    if (deckError) { setError(deckError); return }

    const joinMsg = {
      type: 'JOIN_GAME',
      gameId: code,
      playerName: playerName.trim(),
      deckList: deckList.trim(),
      ...(format === 'COMMANDER' && { commander: commander.trim() }),
    }

    connect(code, joinMsg, () => {
      navigate(`/game/${code}`, { state: { playerName, format } })
    })
  }

  return (
    <div className="min-h-screen bg-gray-950 flex items-center justify-center p-4">
      <div className="w-full max-w-2xl">
        {/* Header */}
        <div className="text-center mb-8">
          <h1 className="text-4xl font-bold text-yellow-400 font-serif tracking-wide mb-1">
            MTG Gameplay
          </h1>
          <p className="text-gray-500 text-sm">Powered by XMage</p>
        </div>

        <div className="bg-gray-900 border border-gray-800 rounded-2xl p-6 shadow-2xl">
          {/* Player Name */}
          <div className="mb-5">
            <label className="block text-gray-400 text-sm mb-1.5 font-medium">Player Name</label>
            <input
              type="text"
              value={playerName}
              onChange={(e) => setPlayerName(e.target.value)}
              placeholder="Enter your name..."
              className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-gray-200
                focus:outline-none focus:border-yellow-600 text-sm"
            />
          </div>

          {/* Format selector */}
          <div className="mb-5">
            <label className="block text-gray-400 text-sm mb-1.5 font-medium">Format</label>
            <div className="grid grid-cols-3 gap-2">
              {Object.entries(FORMAT_INFO).map(([key, info]) => (
                <button
                  key={key}
                  onClick={() => setFormat(key)}
                  className={`p-3 rounded-lg border text-left transition-colors
                    ${format === key
                      ? 'bg-yellow-900/40 border-yellow-600 text-yellow-300'
                      : 'bg-gray-800 border-gray-700 text-gray-400 hover:border-gray-600'}`}
                >
                  <div className="font-semibold text-sm mb-1">{info.label}</div>
                  <div className="text-[11px] leading-tight opacity-80">{info.description}</div>
                </button>
              ))}
            </div>
          </div>

          {/* Commander input */}
          {format === 'COMMANDER' && (
            <div className="mb-5">
              <label className="block text-gray-400 text-sm mb-1.5 font-medium">Commander</label>
              <input
                type="text"
                value={commander}
                onChange={(e) => setCommander(e.target.value)}
                placeholder="e.g. Atraxa, Praetors' Voice"
                className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-gray-200
                  focus:outline-none focus:border-yellow-600 text-sm"
              />
            </div>
          )}

          {/* Deck list */}
          <div className="mb-5">
            <label className="block text-gray-400 text-sm mb-1.5 font-medium">
              Deck List{' '}
              <span className="text-gray-600 font-normal">(one card per line: "4 Lightning Bolt")</span>
            </label>
            <textarea
              value={deckList}
              onChange={(e) => setDeckList(e.target.value)}
              placeholder={`4 Lightning Bolt\n4 Counterspell\n20 Island\n...`}
              rows={8}
              className="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-gray-200
                focus:outline-none focus:border-yellow-600 text-sm font-mono resize-y"
            />
          </div>

          {/* Error */}
          {error && (
            <div className="mb-4 p-3 bg-red-950 border border-red-800 rounded-lg text-red-300 text-sm">
              {error}
            </div>
          )}

          {/* Tabs: Create / Join */}
          <div className="flex gap-2 mb-4">
            <button
              onClick={() => setTab('create')}
              className={`flex-1 py-2 rounded-lg text-sm font-semibold transition-colors
                ${tab === 'create'
                  ? 'bg-yellow-700 text-yellow-100'
                  : 'bg-gray-800 text-gray-500 hover:text-gray-300'}`}
            >
              Create Game
            </button>
            <button
              onClick={() => setTab('join')}
              className={`flex-1 py-2 rounded-lg text-sm font-semibold transition-colors
                ${tab === 'join'
                  ? 'bg-yellow-700 text-yellow-100'
                  : 'bg-gray-800 text-gray-500 hover:text-gray-300'}`}
            >
              Join Game
            </button>
          </div>

          {tab === 'create' ? (
            <div>
              <button
                onClick={handleCreate}
                className="w-full py-3 bg-yellow-600 hover:bg-yellow-500 text-black font-bold rounded-lg
                  text-sm transition-colors shadow-lg"
              >
                Create Game
              </button>
              {createdGameId && (
                <div className="mt-3 p-3 bg-green-950 border border-green-800 rounded-lg text-center">
                  <p className="text-green-400 text-sm mb-1">Game created! Share this code:</p>
                  <p className="text-green-300 font-bold text-2xl tracking-widest">{createdGameId}</p>
                </div>
              )}
            </div>
          ) : (
            <div className="flex gap-2">
              <input
                type="text"
                value={joinCode}
                onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
                placeholder="Game code..."
                maxLength={10}
                className="flex-1 bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-gray-200
                  focus:outline-none focus:border-yellow-600 text-sm font-mono tracking-widest uppercase"
              />
              <button
                onClick={handleJoin}
                className="px-6 py-2 bg-yellow-600 hover:bg-yellow-500 text-black font-bold rounded-lg
                  text-sm transition-colors"
              >
                Join
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
