# Production service operations

## Service map

The current installation serves `asteride.cn` as the canonical HTTPS website. `asteride.top` and `astercode.top` redirect to it. Nginx also proxies the account API to loopback port 8787 and Terminal MCP to loopback port 8893. Do not expose those loopback listeners directly to the public network.

The website uses `/var/www/aster/current`, pointing into a releases directory. The account service uses `/opt/aster-service/current/aster-service` with working state under `/var/lib/aster-service`. Terminal MCP uses `/opt/aster-terminal-mcp/current/server.py`, a separate virtual environment and working state under `/var/lib/aster-terminal-mcp`.

Never commit server passwords, environment files, service tokens, databases, private TLS keys or shell history. Service source and deployment records should contain paths and hashes, not credentials.

## Baseline before change

Record the resolved current release links, hashes of deployed application files, service active states, Nginx syntax and HTTP status checks. Check the canonical domain and both redirect domains. An unauthenticated MCP request should be rejected, normally with HTTP 401. A healthy homepage alone does not demonstrate account or MCP functionality.

Compare server files against the intended local files individually. An unchanged server entry point does not imply its imported libraries are unchanged. In the inspected installation, the MCP entry point, requirements and terminal engine match local hashes, while diagnostics and project tool libraries differ. Review those differences before replacing them.

## Safe deployment

Create a new release directory and keep the prior target. Transfer only reviewed application files; never overwrite production environment or state. Verify content hashes and Python syntax in the target environment before switching. For a static site, validate the manifest, internal links, strict CSP compatibility and release metadata. Download links must continue to describe an actually published APK, not an unbuilt development snapshot.

Switch the current symlink atomically. Validate Nginx with `nginx -t` before reload. Reload Nginx only when configuration changed. Restart a backend only when changed backend code requires it. Restarting Terminal MCP terminates live PTYs: retained history does not preserve running processes. Inspect active usage and account for this impact before scheduling a restart.

After a backend update, check service state, local authenticated health without printing credentials, external authentication rejection, representative protocol calls and website/API routing. If validation fails, restore the old link and restart the old service. Record failures and rollback results.

## Shared-host constraints

The server also runs PostgreSQL and certificate automation. Preserve their configuration and data. Do not run broad package upgrades, firewall changes, database migrations or certificate renewal merely as part of a website update. Those changes require a concrete task and a verified rollback plan.

The account service is a compiled executable accompanied by a source archive. Identify the source revision and build recipe before replacing it. A phone-side Agent UI fix alone does not justify changing the account database or backend protocol.

## Verification record

Store deployment time, source revision, prior and new release paths, file hashes, health results and rollback command references. Keep this record free of secrets. Verify each domain independently; a successful canonical request does not establish that redirect certificates are valid.