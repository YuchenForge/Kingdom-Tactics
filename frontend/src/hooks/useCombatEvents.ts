import { useQuery } from '@tanstack/react-query'
import { loadCombatRecording } from '../api/combatRecording'

/** A disabled/new round never exposes the previous round's recording. */
export function useCombatEvents(gameId: string | undefined, roundNumber: number | null) {
  return useQuery({
    queryKey: ['combatEvents', gameId, roundNumber],
    queryFn: ({ signal }) => loadCombatRecording(gameId!, roundNumber!, signal),
    enabled: !!gameId && roundNumber != null,
    staleTime: Infinity,
    retry: false, // Page-level retries retain the last successful cursor.
  })
}
