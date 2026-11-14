/** Reads the XSRF token cookie the server issued, or an empty string when absent. */
export function readXsrfToken(cookies: string): string {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]+)/.exec(cookies);
  return match ? decodeURIComponent(match[1]) : '';
}
