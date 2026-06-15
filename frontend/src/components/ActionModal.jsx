import React, { useState } from 'react'

/**
 * Modal shown when the server sends WAITING_ACTION.
 * Handles: CHOOSE, DECLARE_ATTACKERS, DECLARE_BLOCKERS, etc.
 */
export default function ActionModal({ waitingAction, onSubmit, onClose }) {
  const [selectedOption, setSelectedOption] = useState(null)
  const [inputValue, setInputValue] = useState('')

  if (!waitingAction) return null

  const { actionType, options = [], prompt } = waitingAction

  const handleSubmit = () => {
    if (actionType === 'CHOOSE') {
      onSubmit({ choiceId: waitingAction.choiceId, value: selectedOption ?? inputValue })
    } else {
      onSubmit({ actionType, value: selectedOption ?? inputValue })
    }
  }

  return (
    <div className="fixed inset-0 bg-black/75 z-[100] flex items-center justify-center p-4">
      <div className="bg-gray-900 border border-yellow-800 rounded-xl p-5 max-w-md w-full shadow-2xl">
        <h2 className="text-yellow-400 font-bold text-lg mb-1">Action Required</h2>
        <p className="text-gray-300 text-sm mb-4">{prompt ?? actionType}</p>

        {options.length > 0 ? (
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
        ) : (
          <input
            type="text"
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            placeholder="Enter value..."
            className="w-full bg-gray-800 border border-gray-700 rounded-md px-3 py-2 text-gray-200 text-sm mb-4
              focus:outline-none focus:border-yellow-600"
          />
        )}

        <div className="flex gap-2 justify-end">
          {onClose && (
            <button
              onClick={onClose}
              className="px-4 py-2 bg-gray-800 hover:bg-gray-700 text-gray-300 rounded-md text-sm"
            >
              Cancel
            </button>
          )}
          <button
            onClick={handleSubmit}
            disabled={options.length > 0 && selectedOption === null}
            className="px-4 py-2 bg-yellow-600 hover:bg-yellow-500 disabled:opacity-40
              text-black font-bold rounded-md text-sm transition-colors"
          >
            Confirm
          </button>
        </div>
      </div>
    </div>
  )
}
