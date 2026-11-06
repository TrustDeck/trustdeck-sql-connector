#!/bin/sh
# Runs Maven inside the image build with the proxy passed as Docker build argument.
#
# Maven ignores HTTP(S)_PROXY, so this script translates the build's proxy
# variables into a temporary settings.xml. A settings.xml mounted as the
# "maven_settings" build secret takes precedence and is used unchanged.
# Without a proxy, Maven runs as usual.
set -eu

if [ -s /root/.m2/settings.xml ]; then
	exec mvn "$@"
fi

proxy="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [ -z "$proxy" ]; then
	exec mvn "$@"
fi

# http://[user:password@]host[:port][/]
address="${proxy#*://}"
address="${address%%/*}"
user=""
password=""
case "$address" in
	*@*)
		credentials="${address%@*}"
		address="${address##*@}"
		user="${credentials%%:*}"
		password="${credentials#*:}"
		;;
esac
case "$address" in
	*:*) host="${address%:*}"; port="${address##*:}" ;;
	*) host="$address"; port=80 ;;
esac

# NO_PROXY is comma-separated with leading dots; Maven expects '|' and '*.' wildcards.
# CIDR ranges have no Maven equivalent and are skipped.
non_proxy=$(printf '%s' "${NO_PROXY:-${no_proxy:-}}" | tr ',' '\n' | sed -e 's/^ *//' -e 's/ *$//' -e 's/^\./*./' \
	| grep -v -e '/' -e '^$' | paste -sd '|' - || true)

settings=$(mktemp /tmp/maven-proxy-settings.XXXXXX.xml)
trap 'rm -f "$settings"' EXIT

{
	echo '<settings><proxies>'
	for protocol in http https; do
		echo "<proxy><id>build-$protocol</id><active>true</active><protocol>$protocol</protocol>"
		echo "<host>$host</host><port>$port</port>"
		[ -n "$user" ] && echo "<username>$user</username><password>$password</password>"
		[ -n "$non_proxy" ] && echo "<nonProxyHosts>$non_proxy</nonProxyHosts>"
		echo '</proxy>'
	done
	echo '</proxies></settings>'
} > "$settings"

mvn -s "$settings" "$@"
