import React, { useState } from 'react'
import Card from './Card'
import ManaPool from './ManaPool'
import CommanderZone from './CommanderZone'

// Sort battlefield cards: Lands → Creatures → Artifacts → Enchantments → Planeswalkers → Others
function sortBattlefield(cards = []) {
  const order = (types = []) => {
    const t = types.map((s) => s.toLowerCase())
    if (t.includes('land')) return 0
    if (t.includes('creature')) return 1
    if (t.includes('artifact')) return 2
    if (t.includes('enchantment')) return 3
    if (t.includes('planeswalker')) return 4
    return 5
  }
  return [...cards].sort((a, b) => order(a.types) - order(b.types))
}

export default function PlayerArea({
  player,
  isOpponent = false,
  format,
  isActive = false,
  hasPriority = false,
  onCardClick,
  onCommanderClick,
  onLifeChange,
  playableCardIds = new Set(),
  targetableCardIds = new Set(),
  selectedCardId = null,
  localPlayerId,
}) {
  const [showGraveyard, setShowGraveyard] = useState(false)
  const [showExile, setShowExile] = useState(false)

  const sortedBattlefield = sortBattlefield(player.battlefield)
  const isCommander = format === 'COMMANDER'

  const lifeColor =
    player.life > 20
      ? 'text-green-400'
      : player.life > 10
      ? 'text-yellow-400'
      : player.life > 5
      ? 'text-orange-400'
      : 'text-red-500'

  return (
    <div
      className={`flex flex-col gap-2 p-2 rounded-xl border transition-colors
        ${isActive ? 'border-yellow-700 bg-yellow-950/10' : 'border-gray-800 bg-gray-900/30'}
        ${hasPriority ? 'ring-1 ring-yellow-500' : ''}
        ${isOpponent ? 'flex-col-reverse' : ''}`}
    >
      {/* Player info bar */}
      <div className="flex items-center justify-between gap-2 px-1">
        <div className="flex items-center gap-2">
          <span className={`font-bold text-sm ${isActive ? 'text-yellow-300' : 'text-gray-300'}`}>
            {player.name}
            {isActive && <span className="ml-1 text-[10px] text-yellow-500">(active)</span>}
            {hasPriority && <span className="ml-1 text-[10px] text-yellow-400">*priority*</span>}
          </span>
        </div>

        {/* Life total */}
        <div className="flex items-center gap-1">
          {!isOpponent && (
            <button
              onClick={() => onLifeChange?.(player.id, -1)}
              className="w-6 h-6 bg-gray-800 hover:bg-red-900 text-gray-300 rounded text-sm font-bold"
            >
              -
            </button>
          )}
          <span
            className={`font-bold text-2xl leading-none min-w-[2.5rem] text-center cursor-pointer ${lifeColor}`}
            onClick={() => !isOpponent && onLifeChange?.(player.id, 0)}
            title="Life total"
          >
            {player.life}
          </span>
          {!isOpponent && (
            <button
              onClick={() => onLifeChange?.(player.id, +1)}
              className="w-6 h-6 bg-gray-800 hover:bg-green-900 text-gray-300 rounded text-sm font-bold"
            >
              +
            </button>
          )}
          <span className="text-gray-600 text-xs ml-0.5">life</span>
        </div>

        {/* Mana pool */}
        <ManaPool manaPool={player.manaPool} />

        {/* Zone counts */}
        <div className="flex items-center gap-2 text-xs">
          <button
            onClick={() => setShowGraveyard(!showGraveyard)}
            className="text-gray-500 hover:text-gray-300 transition-colors"
            title="Graveyard"
          >
            GY: {player.graveyard?.length ?? 0}
          </button>
          <button
            onClick={() => setShowExile(!showExile)}
            className="text-gray-500 hover:text-gray-300 transition-colors"
            title="Exile"
          >
            Ex: {player.exile?.length ?? 0}
          </button>
          <span className="text-gray-600" title="Library">
            Lib: {player.library?.count ?? 0}
          </span>
          {!isOpponent && (
            <span className="text-gray-600" title="Hand size">
              Hand: {player.hand?.length ?? 0}
            </span>
          )}
        </div>
      </div>

      {/* Commander damage (Commander format only) */}
      {isCommander && player.commanderDamage && Object.keys(player.commanderDamage).length > 0 && (
        <div className="flex items-center gap-1 px-1">
          <span className="text-[10px] text-gray-600 mr-1">Cmd dmg:</span>
          {Object.entries(player.commanderDamage).map(([cmdId, dmg]) => (
            <span
              key={cmdId}
              className={`text-[10px] px-1.5 py-0.5 rounded border
                ${dmg >= 21 ? 'bg-red-900 border-red-600 text-red-200 font-bold'
                  : dmg >= 15 ? 'bg-orange-900 border-orange-700 text-orange-300'
                  : 'bg-gray-800 border-gray-700 text-gray-400'}`}
              title={`Commander ${cmdId}`}
            >
              {dmg}
            </span>
          ))}
        </div>
      )}

      {/* Commander Zone */}
      {isCommander && player.commandZone?.length > 0 && (
        <CommanderZone
          commandZone={player.commandZone}
          onCardClick={onCommanderClick}
          playable={player.id === localPlayerId}
        />
      )}

      {/* Battlefield */}
      <div className="min-h-[120px] bg-gray-950/50 rounded-lg p-2 border border-gray-800">
        <div className="text-[10px] text-gray-700 uppercase tracking-widest mb-1">Battlefield</div>
        {sortedBattlefield.length === 0 ? (
          <div className="text-gray-800 text-xs italic">empty</div>
        ) : (
          <div className="flex flex-wrap gap-2 items-end">
            {sortedBattlefield.map((card) => (
              <Card
                key={card.id}
                card={card}
                tapped={card.tapped}
                size="md"
                playable={playableCardIds.has(card.id)}
                targetable={targetableCardIds.has(card.id)}
                selected={selectedCardId === card.id}
                onClick={() => onCardClick?.(card)}
              />
            ))}
          </div>
        )}
      </div>

      {/* Hand (only shown for local player, face up) */}
      {!isOpponent && player.hand?.length > 0 && (
        <div className="bg-gray-950/70 rounded-lg p-2 border border-gray-800">
          <div className="text-[10px] text-gray-700 uppercase tracking-widest mb-1">
            Hand ({player.hand.length})
          </div>
          <div className="flex flex-wrap gap-2 items-end">
            {player.hand.map((card) => (
              <Card
                key={card.id}
                card={card}
                size="md"
                playable={playableCardIds.has(card.id)}
                selected={selectedCardId === card.id}
                onClick={() => onCardClick?.(card)}
              />
            ))}
          </div>
        </div>
      )}

      {/* Opponent hand (shown face down as count) */}
      {isOpponent && (player.hand?.length ?? 0) > 0 && (
        <div className="flex items-center gap-1 px-1">
          <span className="text-[10px] text-gray-600">Hand:</span>
          <div className="flex gap-0.5">
            {Array.from({ length: player.hand.length }).map((_, i) => (
              <div
                key={i}
                className="w-8 h-12 bg-gray-800 border border-gray-700 rounded-md
                  bg-gradient-to-br from-gray-700 to-gray-900"
              />
            ))}
          </div>
        </div>
      )}

      {/* Graveyard popup */}
      {showGraveyard && player.graveyard?.length > 0 && (
        <ZonePile
          title="Graveyard"
          cards={player.graveyard}
          onClose={() => setShowGraveyard(false)}
          onCardClick={onCardClick}
        />
      )}

      {/* Exile popup */}
      {showExile && player.exile?.length > 0 && (
        <ZonePile
          title="Exile"
          cards={player.exile}
          onClose={() => setShowExile(false)}
          onCardClick={onCardClick}
        />
      )}
    </div>
  )
}

function ZonePile({ title, cards = [], onClose, onCardClick }) {
  return (
    <div className="fixed inset-0 bg-black/70 z-50 flex items-center justify-center" onClick={onClose}>
      <div
        className="bg-gray-900 border border-gray-700 rounded-xl p-4 max-w-2xl w-full max-h-[80vh] overflow-y-auto"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-yellow-400 font-bold">{title} ({cards.length})</h3>
          <button onClick={onClose} className="text-gray-500 hover:text-white text-lg">x</button>
        </div>
        <div className="flex flex-wrap gap-2">
          {cards.map((card, idx) => (
            <Card
              key={card.id ?? idx}
              card={card}
              size="md"
              onClick={() => { onCardClick?.(card); onClose() }}
            />
          ))}
        </div>
      </div>
    </div>
  )
}
