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

## 74138 — audited; implementation pending

**TI source:** [SN74LS138](https://www.ti.com/lit/gpn/SN74LS138), datasheet SDLS014 (December 1972, revised March 1988), package pin assignment page 1 and function table page 2.

**Package:** SN74LS138 is offered in D or N 16-pin package; this library uses **DIP16 (N)**. The package pin assignment is explicitly labelled D or N.

**DIP16 N top-view pin table (TI):**

| Pin | Signal | Direction / polarity |
|---:|---|---|
| 1 | A | IN |
| 2 | B | IN |
| 3 | C | IN |
| 4 | G2A | IN, active LOW |
| 5 | G2B | IN, active LOW |
| 6 | G1 | IN, active HIGH |
| 7 | Y7 | OUT, active LOW |
| 8 | GND | IN |
| 9 | Y6 | OUT, active LOW |
| 10 | Y5 | OUT, active LOW |
| 11 | Y4 | OUT, active LOW |
| 12 | Y3 | OUT, active LOW |
| 13 | Y2 | OUT, active LOW |
| 14 | Y1 | OUT, active LOW |
| 15 | Y0 | OUT, active LOW |
| 16 | VCC | IN |

TI defines (G2^* = G2A + G2B). Therefore the enabled condition is exactly G1=H, G2A=L, G2B=L.

**TI function-table rows read (page 2):**

| G1 | G2* | C | B | A | Y0..Y7 |
|---|---|---|---|---|---|
| X | H | X | X | X | H H H H H H H H |
| L | X | X | X | X | H H H H H H H H |
| H | L | L | L | L | L H H H H H H H |
| H | L | L | L | H | H L H H H H H H |
| H | L | L | H | L | H H L H H H H H |
| H | L | L | H | H | H H H L H H H H |
| H | L | H | L | L | H H H H L H H H |
| H | L | H | L | H | H H H H H L H H |
| H | L | H | H | L | H H H H H H L H |
| H | L | H | H | H | H H H H H H H L |

Here H=1, L=0, X=don't-care. The selected output is LOW; all others are HIGH. The first two rows disable all outputs.

**Second-source cross-check:** Nexperia [74HC138/74HCT138](https://assets.nexperia.com/documents/data-sheet/74HC_HCT138.pdf), revision 10 (26 February 2024), pages 3–4, confirms the same 16 terminal positions for its SO16 pinout, the two active-LOW enables plus one active-HIGH enable, the same three disable conditions, and the eight active-LOW decoder rows. Its signal names are A0/A1/A2 and E1/E2/E3; these correspond by pin position and polarity to TI A/B/C and G2A/G2B/G1. TI alone is used for SN74LS138's DIP/N package assignment.

## 74139 — audited; implementation pending

**TI source:** [SN74LS139A](https://www.ti.com/lit/gpn/SN74LS139A), datasheet SDLS013 (December 1972, revised March 1988), DIP/N pin assignment and function table on page 1.

**Package:** TI explicitly lists SN74LS139A in D or N 16-pin package. Library package: **DIP16 (N)**.

**DIP16 N top-view pin table (TI):**

| Pin | Signal | Direction / behavior |
|---:|---|---|
| 1 | 1G | IN, active LOW enable |
| 2 | 1A | IN, select bit A |
| 3 | 1B | IN, select bit B |
| 4 | 1Y0 | OUT, active LOW |
| 5 | 1Y1 | OUT, active LOW |
| 6 | 1Y2 | OUT, active LOW |
| 7 | 1Y3 | OUT, active LOW |
| 8 | GND | IN |
| 9 | 2Y3 | OUT, active LOW |
| 10 | 2Y2 | OUT, active LOW |
| 11 | 2Y1 | OUT, active LOW |
| 12 | 2Y0 | OUT, active LOW |
| 13 | 2B | IN, select bit B |
| 14 | 2A | IN, select bit A |
| 15 | 2G | IN, active LOW enable |
| 16 | VCC | IN |

**TI function table (page 1), independently applies to decoder 1 and decoder 2:**

| G | B | A | Y0 | Y1 | Y2 | Y3 |
|---|---|---|---|---|---|---|
| H | X | X | H | H | H | H |
| L | L | L | L | H | H | H |
| L | L | H | H | L | H | H |
| L | H | L | H | H | L | H |
| L | H | H | H | H | H | L |

H=HIGH, L=LOW, X=irrelevant. Each decoder is independent. When its enable is HIGH, all four outputs are HIGH. When enabled LOW, exactly one output goes LOW, selected by BA=00/01/10/11.

**Second-source cross-check:** Nexperia [74HC139/74HCT139](https://assets.nexperia.com/documents/data-sheet/74HC_HCT139.pdf), Rev.6 (29 February 2024), page 3, pinning/function tables. It independently confirms both decoder enable polarities, the four active-LOW output rows, and the corresponding terminal numbers: 1/15 enables; 2/3 and 14/13 address pairs; 4–7 and 12–9 output groups; 8=GND; 16=VCC. Nexperia uses SO16 package; TI remains the authority for DIP16 N. **Not single-source.**

