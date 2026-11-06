// In-memory session read by the API client for the bearer token. BO-2 fills it at login.
// The organizer is not here: callers pass it to `api()` alongside their query key.
export const session: { token: string | null } = {
  token: null,
}
