import { SERVER_HOST, SERVER_PORT } from "./constants";
import { createProductionApp } from "./app";

const server = createProductionApp();
server.listen(SERVER_PORT, SERVER_HOST, () => {
  console.log(JSON.stringify({ level: "info", event: "server_started", port: SERVER_PORT }));
});

function shutdown(): void {
  server.close(() => process.exit(0));
}

process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
