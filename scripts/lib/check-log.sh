# Sourced by the server test scripts. log_problems <log> prints the warnings and errors in a server
# log and succeeds if there are any, minus the known harmless lines below.
log_problems() {
  grep -Ei '/(WARN|ERROR)\]|exception|failed|couldn.t|unknown or incomplete|incorrect argument|<--\[HERE\]' "$1" \
    | grep -vE "Can't keep up|Ambiguity between arguments|Test failed" \
    | grep -vE '/WARN\]: (\*\*\*\* SERVER IS RUNNING IN OFFLINE/INSECURE MODE!|The server will make no attempt to authenticate usernames\. Beware\.|While this makes the game possible to play without internet access, it also opens up the ability for hackers to connect with any username they choose\.|To change this, set "online-mode" to "true" in the server\.properties file\.)$'
}
