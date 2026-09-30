const app = Application('System Events');
const proc = app.processes.byName("Finder");
const w = proc.windows[0];
console.log(w.role());
