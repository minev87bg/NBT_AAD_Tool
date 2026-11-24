package com.nbtaad.editor.net

/**
 * DRAFT ONLY - not called from anywhere, not reachable from any button. This is the planned shape of the actual
 * mutating step (the one real write in this whole effort), written out now so it can be read and reasoned about
 * before it is finished, rather than appearing fully formed later.
 *
 * Updated against a real run (ZgwBridgeDiag.run against the actual car). Two things changed from the first draft:
 *
 * - The naive one-line edit does not work on this car's dhcpd build (`isc-dhcpd-4.1-ESV-R3`, a vendor-frozen
 *   2011-era release): it does not predefine the symbolic option name `rfc3442-classless-static-routes`. The real
 *   edit is two lines, not one - see step 2.
 * - The real file opens with "File generated automatically - Do not edit", and its own timestamp sat minutes behind
 *   an active lease renewal - evidence something regenerates it around when the AA WiFi service (re)starts, not
 *   only once at the factory. That bears directly on the open "does it stay forever" question from conversation:
 *   whatever this writes may not need an explicit revert to disappear - the car may already be in the habit of
 *   putting this file back to its own default on its own schedule. Still unconfirmed exactly what triggers that
 *   regeneration (every boot? only a WiFi-settings change?) - worth treating as a welcome safety net, not a
 *   substitute for step 4's own explicit revert, until that trigger is actually pinned down.
 *
 * The real file, as read:
 *     # !! File generated automatically !!
 *     # !! Do not edit !!
 *
 *     authoritative;
 *     max-lease-time 259200;
 *     default-lease-time 259200;
 *     ddns-update-style none;
 *     subnet 172.16.224.0 netmask 255.255.255.0 {
 *        option routers 0.0.0.0;
 *        option domain-name-servers 172.16.224.1;
 *        range 172.16.224.100 172.16.224.254;
 *     }
 * (worth noting in passing: `option routers 0.0.0.0` is BMW deliberately telling connecting phones "no default
 * gateway here" - consistent with NetDiag's own finding that AA WiFi never offers one. This plan doesn't touch that
 * line or restore a general gateway; it adds one specific, narrowly-scoped route to one specific /16, which is a
 * different and much smaller claim than "give phones a way out to the internet via the car".)
 *
 * The mechanism, in order:
 *
 * 1. BACK UP. Copy the real file to a timestamped sibling (`dhcpd.uap0.conf.bak.<unix time>`) before touching
 *    anything. Never skipped, never optional - what makes every other step here reversible by one `cp` back.
 *
 * 2. INSERT TWO LINES, not one:
 *        option rfc3442-classless-static-routes code 121 = array of integer 8;      <- after "ddns-update-style none;"
 *        option rfc3442-classless-static-routes 16, 192, 168, 172, 16, 224, 1;      <- after "option domain-name-servers ...;", inside the subnet block
 *    Line 1 teaches this old dhcpd build the option by name (plain, portable ISC dhcpd grammar - not a hack, and
 *    confirmed harmless to redeclare even on builds that already know the name, per a local test against a newer
 *    dhcpd build). Line 2 is the actual RFC 3442 payload: "16" = /16 prefix, "192, 168" = the destination network
 *    (192.168.0.0/16, already covering ZGW's 192.168.0.8 per NetDiag), "172, 16, 224, 1" = the gateway to send it to
 *    (the head unit's own uap0 address - "send it to me"). The head unit does the rest on its own: forwarding is
 *    already globally on, and its wired side already has that whole /16 on-link.
 *    ZgwBridgeDiag's own dry-run test builds exactly this candidate (by reading the real file line-by-line and
 *    inserting after these same two anchor lines) and syntax-checks it with `dhcpd -t` against a /tmp scratch copy
 *    - the real edit should reuse that same insertion logic verbatim rather than duplicating it by hand.
 *
 * 3. MAKE IT TAKE EFFECT. ISC dhcpd does not reliably hot-reload a config change - the running process needs to
 *    re-read the file, which in practice means restarting it. Its PID is recoverable from `/tmp/dhcpd.pid.uap0`
 *    (the `-pf` path NetDiag's pidin dump showed it started with). Plan: read that PID, stop it, then re-run the
 *    EXACT command line pidin showed it running with -
 *        dhcpd -f -d -cf /var/opt/conn/dhcpd.uap0.conf -lf /var/opt/conn/dhcpd.uap0.leases -pf /tmp/dhcpd.pid.uap0 uap0
 *    (backgrounded) - deliberately not assuming a supervisor restarts it automatically, since that's unconfirmed.
 *    This is the one moment of disruption: a phone mid-lease-renewal on uap0 right then could see a brief drop, on
 *    the order of a small text file being re-read and a socket re-bound - well under the multi-minute hangs the
 *    SSH-based restarts have caused, but not literally zero. The 3-day default/max lease time (259200s) means a
 *    phone that's been continuously associated for a while won't naturally re-ask for a while either - in practice
 *    AA WiFi connections are re-established most drives, so this is likely a non-issue, but not a guarantee.
 *
 * 4. REVERT (the other half of the plan - see the open "ephemeral vs permanent" question from conversation, and the
 *    auto-regeneration evidence above). Copy the backup back over the live file and repeat step 3's restart.
 *    Whether this runs automatically right after each ZGW-reset use, or is left for the car's own regeneration to
 *    clean up, is a product decision, not a technical one - both are the same shell, just a different trigger.
 *
 * None of this runs yet. It gets wired to its own clearly-labeled, separately confirmed action - not folded
 * invisibly into the existing reset button - once there's agreement on the ephemeral-vs-permanent question above.
 */
object ZgwBridgeApply {
    // Intentionally empty for now - see doc comment above.
}
