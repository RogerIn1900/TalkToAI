"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const constants_1 = require("./constants");
const app_1 = require("./app");
const server = (0, app_1.createProductionApp)();
server.listen(constants_1.SERVER_PORT, constants_1.SERVER_HOST, () => {
    console.log(JSON.stringify({ level: "info", event: "server_started", port: constants_1.SERVER_PORT }));
});
function shutdown() {
    server.close(() => process.exit(0));
}
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);
