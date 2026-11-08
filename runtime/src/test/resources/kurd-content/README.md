CAR fixtures from [ipfs/gateway-conformance](https://github.com/ipfs/gateway-conformance)
at commit `eaca278518dc977db10bfec4acd79c92c4ee11ac` (`fixtures/trustless_gateway_car/` and
`fixtures/path_gateway_unixfs/`), produced by Kubo. Dual licensed MIT / Apache-2.0 by their authors.
Unmodified; used to check the wallet's CAR and UnixFS verifier against the reference implementation.

`site-with-404.car` is the wallet's own: a directory holding `index.html` and `404.html`, added with
Kubo 0.43.1 (`ipfs add -r --cid-version 1 --raw-leaves`, root
`bafybeiezwrv6dr544fbj4xtthdg63jblfzqj5j6cqfkslbf7hkbevgpkui`) and exported with `ipfs dag export`.
