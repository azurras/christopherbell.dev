# Location ZIP Coordinates

`2025_Gaz_zcta_national.txt` is the 2025 Census Gazetteer ZIP Code Tabulation
Area file. The Location ZIP coordinate import reads `GEOID`, `INTPTLAT`, and
`INTPTLONG`, then stores those reference coordinates in MongoDB.

This file provides Census ZCTA internal points, not authoritative USPS delivery
geometry. Public Location ZIP lookups read imported Mongo rows instead of
parsing this file during requests.
