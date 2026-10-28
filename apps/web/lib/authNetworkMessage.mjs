export function authNetworkMessage(isOnline) {
  return isOnline
    ? "The account service is temporarily unavailable. You can keep checking messages without an account; please try again later."
    : "Could not reach the server. Check your connection and try again.";
}
