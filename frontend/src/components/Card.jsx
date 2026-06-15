import React, { useState, useRef, useCallback } from 'react'

// Scryfall image URL builder
// scryfallId expected; falls back to name search if not provided
function buildImageUrl(card) {
  if (card.scryfallId) {
    const id = card.scryfallId
    return `https://cards.scryfall.io/normal/front/${id[0]}/${id[1]}/${id}.jpg`
  }
  // Fallback: Scryfall named search redirect (won't work as <img src> directly, used as placeholder)
  return null
}

// Tooltip cache
const oracleCache = {}

async function fetchOracleText(name) {
  if (oracleCache[name] !== undefined) return oracleCache[name]
  try {
    const res = await fetch(`https://api.scryfall.com/cards/named?exact=${encodeURIComponent(name)}`)
    if (!res.ok) throw new Error('Not found')
    const data = await res.json()
    const text = data.oracle_text ?? data.card_faces?.[0]?.oracle_text ?? ''
    oracleCache[name] = { oracleText: text, typeLine: data.type_line ?? '', manaCost: data.mana_cost ?? '' }
  } catch {
    oracleCache[name] = null
  }
  return oracleCache[name]
}

export default function Card({
  card,
  tapped = false,
  playable = false,
  targetable = false,
  selected = false,
  onClick,
  size = 'md',
  className = '',
}) {
  const [tooltip, setTooltip] = useState(null)
  const [tooltipPos, setTooltipPos] = useState({ x: 0, y: 0 })
  const [imgError, setImgError] = useState(false)
  const hoverTimerRef = useRef(null)

  const imageUrl = buildImageUrl(card)

  const sizeClasses = {
    sm: 'w-14 h-20',
    md: 'w-20 h-28',
    lg: 'w-28 h-40',
  }

  const handleMouseEnter = useCallback(async (e) => {
    const rect = e.currentTarget.getBoundingClientRect()
    setTooltipPos({ x: rect.right + 8, y: rect.top })
    hoverTimerRef.current = setTimeout(async () => {
      const data = await fetchOracleText(card.name)
      setTooltip(data)
    }, 400)
  }, [card.name])

  const handleMouseLeave = useCallback(() => {
    clearTimeout(hoverTimerRef.current)
    setTooltip(null)
  }, [])

  const ringClass = selected
    ? 'ring-2 ring-yellow-400'
    : targetable
    ? 'ring-2 ring-red-500'
    : playable
    ? 'ring-2 ring-green-400'
    : ''

  const glowClass = playable
    ? 'shadow-[0_0_8px_2px_rgba(100,200,100,0.6)]'
    : targetable
    ? 'shadow-[0_0_8px_2px_rgba(200,100,100,0.7)]'
    : 'shadow-[0_4px_16px_rgba(0,0,0,0.7)]'

  return (
    <div
      className={`relative inline-block cursor-pointer select-none transition-transform duration-150
        ${tapped ? 'rotate-90' : ''}
        ${sizeClasses[size]}
        ${ringClass}
        ${glowClass}
        rounded-lg overflow-visible
        hover:scale-110 hover:z-50
        ${className}`}
      onClick={onClick}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
      title={card.name}
    >
      {imageUrl && !imgError ? (
        <img
          src={imageUrl}
          alt={card.name}
          onError={() => setImgError(true)}
          className="w-full h-full object-cover rounded-lg"
          draggable={false}
        />
      ) : (
        <CardFallback card={card} size={size} />
      )}

      {/* Tapped overlay */}
      {tapped && (
        <div className="absolute inset-0 bg-black/20 rounded-lg pointer-events-none" />
      )}

      {/* Counters badge */}
      {card.counters && Object.keys(card.counters).length > 0 && (
        <div className="absolute bottom-0 right-0 flex flex-wrap gap-0.5 p-0.5">
          {Object.entries(card.counters).map(([type, count]) => (
            <span
              key={type}
              className="bg-black/80 text-yellow-300 text-[9px] font-bold px-1 rounded border border-yellow-700"
              title={`${type} counters`}
            >
              {type === '+1/+1' ? `+${count}` : `${type}:${count}`}
            </span>
          ))}
        </div>
      )}

      {/* Tooltip */}
      {tooltip && (
        <div
          className="fixed z-[9999] w-56 bg-gray-900 border border-yellow-800 rounded-lg p-3 shadow-2xl pointer-events-none text-left"
          style={{ left: Math.min(tooltipPos.x, window.innerWidth - 240), top: Math.max(4, tooltipPos.y) }}
        >
          <p className="font-bold text-yellow-300 text-sm mb-1">{card.name}</p>
          {tooltip.manaCost && (
            <p className="text-gray-400 text-xs mb-1">{tooltip.manaCost}</p>
          )}
          {tooltip.typeLine && (
            <p className="text-gray-300 text-xs italic mb-1">{tooltip.typeLine}</p>
          )}
          {tooltip.oracleText && (
            <p className="text-gray-200 text-xs leading-relaxed whitespace-pre-line">{tooltip.oracleText}</p>
          )}
        </div>
      )}
    </div>
  )
}

function CardFallback({ card, size }) {
  const sizeClasses = { sm: 'text-[8px]', md: 'text-[10px]', lg: 'text-xs' }
  const bgByType = (types = []) => {
    const t = types.map((s) => s.toLowerCase())
    if (t.includes('creature')) return 'from-green-900 to-green-950'
    if (t.includes('instant')) return 'from-blue-900 to-blue-950'
    if (t.includes('sorcery')) return 'from-red-900 to-red-950'
    if (t.includes('enchantment')) return 'from-purple-900 to-purple-950'
    if (t.includes('artifact')) return 'from-gray-700 to-gray-900'
    if (t.includes('planeswalker')) return 'from-yellow-900 to-yellow-950'
    if (t.includes('land')) return 'from-amber-900 to-amber-950'
    return 'from-gray-800 to-gray-950'
  }

  return (
    <div
      className={`w-full h-full rounded-lg bg-gradient-to-b ${bgByType(card.types)}
        border border-gray-600 flex flex-col items-center justify-between p-1`}
    >
      <span className={`font-bold text-yellow-300 text-center leading-tight ${sizeClasses[size]}`}>
        {card.name}
      </span>
      {card.manaCost && (
        <span className={`text-gray-400 ${sizeClasses[size]}`}>{card.manaCost}</span>
      )}
      <span className={`text-gray-500 italic ${sizeClasses[size]}`}>
        {card.types?.join(' ') ?? 'Card'}
      </span>
    </div>
  )
}
