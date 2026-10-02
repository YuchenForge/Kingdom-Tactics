/** Display copy only; all numeric unit stats come from the API. */
export function abilityDescription(ability: string | null, healAmount: number): string {
  switch (ability) {
    case 'HealEvery3rdAttack': return `Every third action heals the lowest-HP ally instead of attacking. Heal: ${healAmount}.`
    case 'SplashEvery3rdAttack': return 'Every third attack also damages other enemies directly adjacent to the target (not diagonally). Splash ignores armor.'
    case 'LowestHpTarget': return 'Targets the lowest-HP enemy in range; otherwise pursues the lowest-HP enemy.'
    case 'NearestTarget': return 'Targets the nearest enemy.'
    case 'Armor': return 'Armor reduces incoming attack damage.'
    case 'None': case null: case '': return 'No special ability.'
    default: return ability.replace(/([a-z])([A-Z])/g, '$1 $2')
  }
}
