# TI 74xx tier-A datasheet audit

This file records only pin/function information read from manufacturer datasheets.
A device must not be added to the library until its own entry is present here and
the matching `PROGRESS:` comment has been updated.

## 7442 — audited; implementation pending

**TI source:** [SN7442A / SN74LS42, 4-Line BCD to 10-Line Decimal Decoders](https://www.ti.com/lit/gpn/SN74LS42) (SDLS109, revised March 1988), package/pin-configuration page 1 and function-table page 4.

**Package:** SN7442A is N-package DIP16; SN74LS42 is D or N package. This library uses DIP16.

**DIP16 pin table (TI top view):**

| Pin | Signal | Direction |
|---:|---|---|
| 1 | 0 | OUT |
| 2 | 1 | OUT |
| 3 | 2 | OUT |
| 4 | 3 | OUT |
| 5 | 4 | OUT |
| 6 | 5 | OUT |
| 7 | 6 | OUT |
| 8 | GND | IN |
| 9 | 7 | OUT |
| 10 | 8 | OUT |
| 11 | 9 | OUT |
| 12 | D | IN |
| 13 | C | IN |
| 14 | B | IN |
| 15 | A | IN |
| 16 | VCC | IN |

**TI function-table rows read (D C B A; outputs 0 through 9):**

| Decimal row | D C B A | Output driven LOW |
|---:|:---:|---:|
| 0 | 0000 | 0 |
| 1 | 0001 | 1 |
| 2 | 0010 | 2 |
| 3 | 0011 | 3 |
| 4 | 0100 | 4 |
| 5 | 0101 | 5 |
| 6 | 0110 | 6 |
| 7 | 0111 | 7 |
| 8 | 1000 | 8 |
| 9 | 1001 | 9 |

For invalid BCD input rows 1010 through 1111, TI specifies all ten outputs HIGH.
Each valid row has exactly one LOW output; all other decimal outputs are HIGH.

**Second-source check:** no independent manufacturer pin-and-function sheet was available in this audit. Mark this part **single-source** (TI only); no secondary source was used to invent or fill any row.
