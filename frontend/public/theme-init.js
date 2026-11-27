// Applies the saved theme before the first paint so there is no flash of the wrong one. It is an external file
// because the CSP allows only `script-src 'self'`. Keep the ids and colours in step with core/theme/theme.model.ts;
// theme-init.spec.ts fails when they drift.
(function () {
  var themes = { dark: '#0b0f0e', amoled: '#000000' };
  var id = 'dark';
  try {
    var stored = localStorage.getItem('modsync.theme');
    if (Object.prototype.hasOwnProperty.call(themes, stored)) {
      id = stored;
    }
  } catch (e) {
    // Storage is blocked: the default theme applies.
  }
  var root = document.documentElement;
  root.setAttribute('data-theme', id);
  root.setAttribute('data-bs-theme', 'dark');
  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) {
    meta.setAttribute('content', themes[id]);
  }
})();
