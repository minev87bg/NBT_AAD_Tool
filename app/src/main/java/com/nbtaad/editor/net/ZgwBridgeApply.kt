package com.nbtaad.editor.net

/**
 * DRAFT ONLY - not called from anywhere, not reachable from any button. This is the planned shape of the actual
 * mutating step (the one real write in this whole effort), written out now so it can be read and reasoned about
 * before it is finished, rather than appearing fully formed later. It is deliberately incomplete in one place (see
 * TODO below) until ZgwBridgeDiag has actually been run once against the real head unit and its real output is in
 * hand - writing the exact command before that would be guessing at a live vehicle's config file layout.
 *
 * The mechanism, in order:
 *
 * 1. BACK UP. Copy the real file to a timestamped sibling (`dhcpd.uap0.conf.bak.<unix time>`) before touching
 *    anything. This is the one step that is never skipped and never optional - it is what makes every other step
 *    here reversible by a single `cp` back.
 *
 * 2. INSERT ONE LINE. Add exactly one line:
 *        option rfc3442-classless-static-routes 16, 192, 168, 172, 16, 224, 1;
 *    This is DHCP option 121 (RFC 3442) - a normal, long-standing DHCP option, not anything BMW- or QNX-specific.
 *    Decoded: "16" = /16 prefix length, "192, 168" = the first two octets of the destination network
 *    (192.168.0.0/16, which already covers ZGW's 192.168.0.8 per NetDiag's own findings), "172, 16, 224, 1" = the
 *    gateway a connecting phone should send that traffic to (the head unit's own uap0 address - i.e. "send it to
 *    me"). The head unit already does the rest on its own: forwarding is already globally on, and its wired
 *    interface already has that whole /16 on-link, so once a phone has this route, packets for ZGW simply flow
 *    head-unit-ward instead of nowhere. The line must land inside the right `subnet 172.16.224.0 ... { }` block, not
 *    just appended to the file - TODO: the exact insertion point depends on that block's real layout, which is what
 *    ZgwBridgeDiag.run() exists to capture first.
 *
 * 3. MAKE IT TAKE EFFECT. ISC dhcpd (which this build is) does not reliably hot-reload a config change - the
 *    running process needs to re-read the file, which in practice means restarting it. Its PID is recoverable from
 *    `/tmp/dhcpd.pid.uap0` (the `-pf` path NetDiag's pidin dump showed it was started with). Plan: read that PID,
 *    send it a normal stop signal, then re-run the EXACT same command line pidin showed it running with -
 *        dhcpd -f -d -cf /var/opt/conn/dhcpd.uap0.conf -lf /var/opt/conn/dhcpd.uap0.leases -pf /tmp/dhcpd.pid.uap0 uap0
 *    (backgrounded). This is deliberately NOT "assume something supervises and restarts it automatically" - that is
 *    unconfirmed, so the plan re-launches it explicitly rather than hoping. This step is the one moment of
 *    disruption: any phone mid-lease-renewal on uap0 at that instant could see a brief drop, on the order of the time
 *    it takes a small text file to be re-read and a socket re-bound - well under the multi-minute hangs the SSH-based
 *    restarts have caused, but not literally zero.
 *
 * 4. REVERT (the other half of the plan - see the open "ephemeral vs permanent" question raised in conversation).
 *    Copy the backup back over the live file and repeat step 3's restart. Whether this runs automatically right
 *    after each ZGW-reset use, or is left in place, is a product decision, not a technical one - both are the same
 *    three lines of shell, just in a different order relative to the reset call.
 *
 * None of this runs yet. The next step is a real ZgwBridgeDiag.run() result from the actual car, after which the
 * TODO above gets replaced with the real insertion point and this gets wired to its own clearly-labeled, separately
 * confirmed action - not folded invisibly into the existing reset button.
 */
object ZgwBridgeApply {
    // Intentionally empty for now - see doc comment above.
}
