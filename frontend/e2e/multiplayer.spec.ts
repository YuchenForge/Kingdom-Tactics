import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'

async function register(page: Page, username: string) {
  await page.goto('/auth?mode=register')
  await page.getByLabel('Username', { exact: true }).fill(username)
  await page.getByLabel('Email', { exact: true }).fill(`${username}@example.com`)
  await page.getByLabel('Password', { exact: true }).fill('browser-test-password')
  await page.getByRole('button', { name: 'Register', exact: true }).click()
  await expect(page.getByText(`Welcome, ${username}.`, { exact: true })).toBeVisible()
}

async function deploy(page: Page) {
  await expect(page.getByRole('region', { name: 'Your board', exact: true })).toBeVisible()
  // Recruit a damage-dealing unit so this exercises recorded attacks, not empty boards.
  for (let attempt = 0; attempt < 4; attempt++) {
    const offers = page.getByRole('region', { name: 'Shop', exact: true }).getByRole('article')
    for (let slot = 0; slot < await offers.count(); slot++) {
      const type = await offers.nth(slot).getByRole('heading').innerText()
      if (type === 'Healer' || type === 'HEALER' || type === 'Sold') continue
      await offers.nth(slot).getByRole('button', { name: /^Buy slot/ }).click()
      const unit = page.getByRole('region', { name: 'Holding lane', exact: true }).getByRole('button', { name: /^Select / })
      await expect(unit).toHaveCount(1)
      await unit.click()
      await page.getByRole('button', { name: 'Board x 1 y 3', exact: true }).click()
      await expect(page.getByRole('region', { name: 'Your board', exact: true }).getByRole('button', { name: /^Select / })).toHaveCount(1)
      return
    }
    const refresh = page.getByRole('button', { name: 'Refresh shop · 1 gold', exact: true })
    await refresh.click()
    await expect(refresh).toBeEnabled()
  }
  throw new Error('No damage-dealing recruit found after refreshing the shop')
}

test('two players recruit, reload, watch automatic combat and finish a match', async ({ browser, baseURL }, testInfo) => {
  const contexts = await Promise.all([browser.newContext({ baseURL }), browser.newContext({ baseURL })])
  const pages = await Promise.all(contexts.map(context => context.newPage()))
  const errors: string[] = []
  for (const page of pages) page.on('pageerror', error => errors.push(error.message))
  try {
    const suffix = randomUUID().slice(0, 8)
    await Promise.all(pages.map((page, i) => register(page, `e2e${suffix}${i}`)))
    await pages[0].getByRole('button', { name: 'New game', exact: true }).click()
    const invite = pages[0].getByLabel('Invite link', { exact: true })
    await expect(invite).toHaveValue(/\/join\//)
    await pages[1].getByLabel('Game id or invite link', { exact: true }).fill(await invite.inputValue())
    await pages[1].getByRole('button', { name: 'Join game', exact: true }).click()
    await Promise.all(pages.map(deploy))
    await pages[0].reload()
    await expect(pages[0].getByRole('region', { name: 'Your board', exact: true }).getByRole('button', { name: /^Select / })).toHaveCount(1)

    for (let round = 1; round <= 8; round++) {
      await Promise.all(pages.map(async page => {
        await expect(page.getByRole('region', { name: 'Game status' })).toContainText(`Round ${round}`)
        await expect(page.getByRole('button', { name: 'Lock board', exact: true })).toBeEnabled({ timeout: 90_000 })
      }))
      // Begin observing both players before locking either board; playback is automatic.
      const playbackChecks = pages.map(async page => {
        const combat = page.getByRole('region', { name: 'Merged combat board', exact: true })
        await expect(combat).toBeVisible({ timeout: 90_000 })
        await expect(combat.locator('.combat-sprite')).not.toHaveCount(0)
        await expect(combat).toContainText(/Tick [1-9][0-9]* \/ /, { timeout: 90_000 })
      })
      await Promise.all(pages.map(page => page.getByRole('button', { name: 'Lock board', exact: true }).click()))
      await Promise.all(playbackChecks)
      if (round < 8) {
        await Promise.all(pages.map(async page => {
          await expect(page.getByRole('heading', { name: `Round ${round} result`, exact: true })).toBeVisible({ timeout: 90_000 })
        }))
      }
      // The next rendered destination must be planning or the final result, without clicks.
      await Promise.all(pages.map(page => expect(page.getByRole('region', { name: 'Your board', exact: true })
        .or(page.getByRole('heading', { name: 'Match result', exact: true }))).toBeVisible({ timeout: 90_000 })))
      if (await pages[0].getByRole('heading', { name: 'Match result', exact: true }).isVisible()) break
    }
    for (const page of pages) {
      await expect(page).toHaveURL(/\/result$/)
      await expect(page.getByRole('region', { name: 'Final match outcome' })).toBeVisible()
      await expect(page.getByRole('heading', { name: 'Play again', exact: true })).toBeVisible()
    }
    const outcomes = await Promise.all(pages.map(page => page.getByRole('region', { name: 'Final match outcome' }).getByRole('heading', { level: 2 }).innerText()))
    expect(outcomes.sort()).toEqual(outcomes.includes('Draw') ? ['Draw', 'Draw'] : ['Defeat', 'Victory'])
    expect(errors).toEqual([])
  } finally {
    if (testInfo.status !== testInfo.expectedStatus) {
      for (const [i, page] of pages.entries()) await testInfo.attach(`player-${i + 1}`, { body: await page.screenshot(), contentType: 'image/png' })
    }
    await Promise.all(contexts.map(context => context.close()))
  }
})
