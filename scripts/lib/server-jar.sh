# Sourced by the server and cost scripts: the pinned 26.3 server jar. CI keys its jar cache on
# this file's hash, so changing the version here also refreshes the cache.
SERVER_JAR_SHA1=33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c
SERVER_JAR_URL=https://piston-data.mojang.com/v1/objects/$SERVER_JAR_SHA1/server.jar

# fetch_server_jar: puts a verified server.jar in the current directory, downloading it if needed
fetch_server_jar() {
  echo "$SERVER_JAR_SHA1  server.jar" | sha1sum -c --status 2>/dev/null && return
  curl -fsSL -o server.jar "$SERVER_JAR_URL"
  echo "$SERVER_JAR_SHA1  server.jar" | sha1sum -c --quiet
}
