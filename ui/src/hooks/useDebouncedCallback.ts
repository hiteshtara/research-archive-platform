import { useEffect, useRef } from "react";

// Delays invoking `callback` until `delayMs` has passed since the most
// recent call - each call resets the timer, so only the last one in a
// burst actually fires. Used for live-search text inputs whose onChange
// otherwise commits a URL/query-param change (and therefore a network
// request) on every keystroke - see AwardVersionSearchPage, whose
// unscoped onChange fired one real HTTP request per character before
// this existed.
export function useDebouncedCallback<Args extends unknown[]>(
  callback: (...args: Args) => void,
  delayMs: number,
): (...args: Args) => void {
  const callbackRef = useRef(callback);
  callbackRef.current = callback;

  const timeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );

  useEffect(() => {
    return () => {
      clearTimeout(timeoutRef.current);
    };
  }, []);

  return (...args: Args) => {
    clearTimeout(timeoutRef.current);
    timeoutRef.current = setTimeout(() => {
      callbackRef.current(...args);
    }, delayMs);
  };
}
