import React, { useState } from 'react'

// XMage query types that expect a boolean response
const BOOLEAN_QUERY_TYPES = new Set([
  'ASK', 'CONFIRM', 'PLAY_MANA_HAND_OR_LIBRARY',
])

// Query types where the user picks a target by clicking on the board (dismiss modal)
const CLICK_TARGET_QUERY_TYPES = new Set([
  'SELECT_TARGET', 'PICK_CARD', 'CHOOSE_PILE',
])

export default function ActionModal({ waitingAction, onSubmit, onClose }) {
  const [selectedOption, setSelectedOption] = useState(null)

  if (!waitingAction) return null

  // Support both backend format (queryType/message) and legacy format (actionType/prompt/options)
  const queryType = waitingAction.queryType ?? waitingAction.actionType ?? ''
  const message = waitingAction.message ?? waitingAction.prompt ?? queryType
  const options = waitingAction.options ?? []
  const isBoolean = BOOLEAN_QUERY_TYPES.has(queryType)
  const isClickTarget = CLICK_TARGET_QUERY_TYPES.has(queryType)

  const handleYes = () => onSubmit(true)
  const handleNo = () => onSubmit(false)
  const handleOption = () => onSubmit({ actionType: queryType, value: selectedOption })

  return (
    <div className="fixed inset-0 bg-black/75 z-[100] flex items-center justify-center p-4">
      <div className="bg-gray-900 border border-yellow-800 rounded-xl p-5 max-w-md w-full shadow-2xl">
        <h2 className="text-yellow-400 font-bold text-lg mb-1">Action Required</h2>
        <p className="text-gray-300 text-sm mb-4">{message}</p>

        {isBoolean ? (
          <div className="flex gap-3 justify-end">
            <button
              onClick={handleNo}
              className="px-5 py-2 bg-gray-700 hover:bg-gray-600 text-gray-200 font-bold rounded-md text-sm"
            >
              No
            </button>
            <button
              onClick={handleYes}
              className="px-5 py-2 bg-yellow-600 hover:bg-yellow-500 text-black font-bold rounded-md text-sm"
            >
              Yes
            </button>
          </div>
        ) : isClickTarget ? (
          <p className="text-gray-500 text-xs italic mb-3">
            Click a valid target on the board.
          </p>
        ) : options.length > 0 ? (
          <>
            <div className="flex flex-col gap-2 max-h-60 overflow-y-auto mb-4">
              {options.map((opt, idx) => {
                const val = typeof opt === 'object' ? opt.value ?? opt.id ?? JSON.stringify(opt) : opt
                const label = typeof opt === 'object' ? opt.label ?? opt.name ?? val : opt
                return (
                  <button
                    key={idx}
                    onClick={() => setSelectedOption(val)}
                    className={`px-3 py-2 rounded-md text-sm text-left border transition-colors
                      ${selectedOption === val
                        ? 'bg-yellow-800 border-yellow-500 text-yellow-200'
                        : 'bg-gray-800 border-gray-700 text-gray-300 hover:border-gray-500'}`}
                  >
                    {label}
                  </button>
                )
              })}
            </div>
            <div className="flex gap-2 justify-end">
              {onClose && (
                <button onClick={onClose} className="px-4 py-2 bg-gray-800 hover:bg-gray-700 text-gray-300 rounded-md text-sm">
                  Cancel
                </button>
              )}
              <button
                onClick={handleOption}
                disabled={selectedOption === null}
                className="px-4 py-2 bg-yellow-600 hover:bg-yellow-500 disabled:opacity-40 text-black font-bold rounded-md text-sm"
              >
                Confirm
              </button>
            </div>
          </>
        ) : (
          <div className="flex gap-2 justify-end">
            {onClose && (
              <button onClick={onClose} className="px-4 py-2 bg-gray-800 hover:bg-gray-700 text-gray-300 rounded-md text-sm">
                Dismiss
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
