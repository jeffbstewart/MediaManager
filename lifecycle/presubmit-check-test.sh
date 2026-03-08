#!/usr/bin/env bash
# Unit tests for presubmit-check.sh
#
# Usage: bash lifecycle/presubmit-check-test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$SCRIPT_DIR/presubmit-check.sh"
passed=0
failed=0

# ---------- helpers ----------

expect_pass() {
    local name="$1"
    local input="$2"
    if echo "$input" | bash "$CHECK" > /dev/null 2>&1; then
        passed=$((passed + 1))
    else
        echo "FAIL (expected pass): $name"
        failed=$((failed + 1))
    fi
}

expect_fail() {
    local name="$1"
    local input="$2"
    local expect_label="${3:-}"
    local output
    if output=$(echo "$input" | bash "$CHECK" 2>&1); then
        echo "FAIL (expected fail): $name"
        failed=$((failed + 1))
    else
        if [[ -n "$expect_label" ]] && ! echo "$output" | grep -q "$expect_label"; then
            echo "FAIL (wrong category): $name — expected '$expect_label' in output"
            echo "  Got: $output"
            failed=$((failed + 1))
        else
            passed=$((passed + 1))
        fi
    fi
}

# ---------- test data (constructed via concatenation to avoid self-triggering) ----------

# Banned markers
DNS="DO NOT SUB""MIT"
DNC="DO NOT COM""MIT"
dns_lower="do not sub""mit"

# Sensitive values
IP_PRIV="192.168.1""."100
IP_PUB="54.230.10"".42"
IP_OTHER="10.0.0"".1"
IP_OTHER2="10.0.0"".5"
TEST_UUID="a1b2c3d4-e5f6-7890-abcd-ef12""34567890"
TEST_HEX="abcdef0123456789abcdef01234567""89ab"
TEST_APIKEY="sk-abcdefghijklm""nopqrstuvwxyz"

# ---------- tests ----------

# 1. Clean code passes
expect_pass "clean code" \
    "+val x = 42"

# 2. Banned submit marker triggers
expect_fail "banned submit marker" \
    "+// $DNS: debug only" \
    "Banned marker"

# 3. Banned commit marker triggers
expect_fail "banned commit marker" \
    "+# $DNC" \
    "Banned marker"

# 4. Lowercase banned marker does NOT trigger (case-sensitive, ALL CAPS only)
expect_pass "banned marker lowercase ignored" \
    "+// $dns_lower"

# 4b. Prose containing "do not commit" does NOT trigger
expect_pass "natural prose with commit" \
    "+This is a hard block — do not commit without fixing."

# 5. Private IP address triggers
expect_fail "private IP" \
    "+val server = \"$IP_PRIV\"" \
    "IP address"

# 6. Public IP address triggers
expect_fail "public IP" \
    "+val cdn = \"$IP_PUB\"" \
    "IP address"

# 7. Allowlisted IP (127.0.0.1) passes
expect_pass "localhost IP allowlisted" \
    '+val host = "127.0.0.1"'

# 8. Allowlisted IP (0.0.0.0) passes
expect_pass "any-interface IP allowlisted" \
    '+boot.listenOn = "0.0.0.0"'

# 9. SSDP multicast allowlisted
expect_pass "SSDP multicast allowlisted" \
    '+val MULTICAST = "239.255.255.250"'

# 10. UUID triggers
expect_fail "UUID" \
    "+val apiKey = \"$TEST_UUID\"" \
    "UUID"

# 11. Long hex string triggers
expect_fail "long hex string" \
    "+val hash = \"$TEST_HEX\"" \
    "Long hex string"

# 12. Allowlisted hex string (Vaadin chunk hash) passes
expect_pass "Vaadin chunk hash allowlisted" \
    '+loadOnDemand("039da0aa3283c862d809052d1e05f80ce5922aa4f27e77f287ec5bdfeaba3abc")'

# 13. API key prefix triggers
expect_fail "sk- API key" \
    "+val key = \"$TEST_APIKEY\"" \
    "API key prefix"

# 14. +++ header lines are not checked
expect_pass "file header not checked" \
    '+++ src/main/kotlin/Foo.kt'

# 15. Non-added lines ignored
expect_pass "removed lines ignored" \
    "-val ip = \"$IP_OTHER\""

# 16. Empty diff
expect_pass "empty diff" \
    ""

# 17. Multiple violations in one diff
output=$(printf "+val a = \"$IP_OTHER\"\n+val b = \"$TEST_UUID\"\n" \
    | bash "$CHECK" 2>&1 || true)
if echo "$output" | grep -q "IP address" && echo "$output" | grep -q "UUID"; then
    passed=$((passed + 1))
else
    echo "FAIL: multiple violations — expected both IP and UUID"
    failed=$((failed + 1))
fi

# 18. Mixed clean and dirty lines — only dirty flagged
expect_fail "dirty line in mixed input" \
    "$(printf "+clean code\n+val x = \"$IP_OTHER2\"\n+more clean")" \
    "IP address"

# 19. Version-like strings (e.g. "4.1") should NOT trigger IP check
expect_pass "version number not an IP" \
    '+level 4.1'

# ---------- results ----------

total=$((passed + failed))
echo ""
echo "==============================="
echo "Presubmit tests: $passed/$total passed"
if [[ $failed -gt 0 ]]; then
    echo "$failed FAILED"
    exit 1
else
    echo "All tests passed!"
    exit 0
fi
