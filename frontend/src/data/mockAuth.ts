import type { Store, User } from '../lib/authTypes'

export type MockAuthScenario = {
  salesperson_code: string
  password: string
  user: User
  stores: Store[]
  active_store_id: number | null
}

export const MOCK_AUTH_SCENARIOS: MockAuthScenario[] = [
  {
    salesperson_code: 'LC1',
    password: 'password123',
    user: {
      user_id: 1,
      first_name: 'Liam',
      last_name: 'Carter',
      salesperson_code: 'LC1',
    },
    stores: [
      {
        store_id: 1,
        name: 'Aussie Floors Sydney CBD',
        store_code: 'SYD-CBD',
      },
    ],
    active_store_id: 1,
  },
  {
    salesperson_code: 'MS1',
    password: 'password123',
    user: {
      user_id: 99,
      first_name: 'Morgan',
      last_name: 'Shaw',
      salesperson_code: 'MS1',
    },
    stores: [
      {
        store_id: 1,
        name: 'Aussie Floors Sydney CBD',
        store_code: 'SYD-CBD',
      },
      {
        store_id: 2,
        name: 'Aussie Floors Parramatta',
        store_code: 'SYD-PARR',
      },
    ],
    active_store_id: null,
  },
]
