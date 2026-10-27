const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { getStreams } = require("../providers/resolver.js");

test("manifest exposes the shared resolver once instead of site-name aliases", () => {
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, "../manifest.json"), "utf8"));
  assert.equal(manifest.scrapers.length, 1);
  assert.equal(manifest.scrapers[0].id, "anidap-shared-resolver");
  assert.equal(manifest.scrapers[0].enabled, true);
  assert.deepEqual(manifest.scrapers[0].supportedTypes, ["anime"]);
});

test("shared resolver keeps distinct server/audio variants even when CDN URLs match", async (t) => {
  const previousFetch = global.fetch;
  t.after(() => { global.fetch = previousFetch; });

  global.fetch = async (input) => {
    const url = new URL(String(input));
    let payload;
    if (url.hostname === "api.themoviedb.org" && url.pathname === "/3/tv/991234") {
      payload = { name: "Fixture Anime", original_name: "Fixture Anime", first_air_date: "2020-01-01" };
    } else if (url.hostname === "api.themoviedb.org" && url.pathname.endsWith("/season/1")) {
      payload = { name: "Season 1", air_date: "2020-01-01" };
    } else if (url.pathname === "/api/anime/search") {
      payload = { results: [{ id: "fixture-anime", title: { english: "Fixture Anime", romaji: "Fixture Anime" }, releaseDate: "2020", type: "TV" }] };
    } else if (url.pathname === "/api/anime/fixture-anime") {
      payload = { id: "fixture-anime" };
    } else if (url.pathname === "/rest/api/servers") {
      payload = {
        subProviders: [{ id: "sub-a", name: "Server A" }, { id: "sub-b", name: "Server B" }],
        dubProviders: [{ id: "dub-a", name: "Dub Server" }],
      };
    } else if (url.pathname === "/rest/api/sources") {
      payload = { sources: [{ url: "https://cdn.example/shared.m3u8" }, { url: "https://cdn.example/shared.m3u8" }] };
    } else {
      throw new Error(`Unexpected request: ${url}`);
    }
    return { ok: true, status: 200, json: async () => payload };
  };

  const streams = await getStreams(991234, "anime", 1, 4);

  assert.deepEqual(streams.map((stream) => stream.name).sort(), [
    "Dub Server [DUB]",
    "Server A [SUB]",
    "Server B [SUB]",
  ]);
  assert.equal(streams.length, 3);
  assert.equal(new Set(streams.map((stream) => stream.url)).size, 1);
  assert.ok(streams.every((stream) => stream.provider === "anidap-shared-resolver"));
});
