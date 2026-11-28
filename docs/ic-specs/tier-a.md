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

## 74148 — audited; implementation pending

**TI source:** [SN74LS148](https://www.ti.com/lit/gpn/SN74LS148), datasheet SDLS053B (October 1976, revised May 2004), package pin configuration page 1 and **'148/'LS148 function table page 3**.

**Package:** TI identifies SN74148/SN74LS148 for D, N, or NS package; the order table explicitly lists SN74LS148N as PDIP-N. Library package: **DIP16 (N)**.

**DIP16 N top-view pin table (TI):**

| Pin | Signal | Direction / behavior |
|---:|---|---|
| 1 | 4 | IN, active LOW data input 4 |
| 2 | 5 | IN, active LOW data input 5 |
| 3 | 6 | IN, active LOW data input 6 |
| 4 | 7 | IN, active LOW data input 7 |
| 5 | EI | IN, active LOW enable |
| 6 | A2 | OUT, active LOW encoded bit 2 |
| 7 | A1 | OUT, active LOW encoded bit 1 |
| 8 | GND | IN |
| 9 | A0 | OUT, active LOW encoded bit 0 |
| 10 | 0 | IN, active LOW data input 0 |
| 11 | 1 | IN, active LOW data input 1 |
| 12 | 2 | IN, active LOW data input 2 |
| 13 | 3 | IN, active LOW data input 3 |
| 14 | GS | OUT, active LOW group-select |
| 15 | EO | OUT, enable output |
| 16 | VCC | IN |

**TI function table (page 3), rows transcribed in input order 0,1,2,3,4,5,6,7:**

| EI | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | A2 | A1 | A0 | GS | EO |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| H | X | X | X | X | X | X | X | X | H | H | H | H | H |
| L | H | H | H | H | H | H | H | H | H | H | H | H | L |
| L | X | X | X | X | X | X | X | L | L | L | L | L | H |
| L | X | X | X | X | X | X | L | H | L | L | H | L | H |
| L | X | X | X | X | X | L | H | H | L | H | L | L | H |
| L | X | X | X | X | L | H | H | H | L | H | H | L | H |
| L | X | X | X | L | H | H | H | H | H | L | L | L | H |
| L | X | X | L | H | H | H | H | H | H | L | H | L | H |
| L | X | L | H | H | H | H | H | H | H | H | L | L | H |
| L | L | H | H | H | H | H | H | H | H | H | H | L | H |

H=HIGH, L=LOW, X=irrelevant. Input 0 has highest priority; input 7 has lowest priority. With EI=H the encoder is disabled and all five outputs are HIGH. With EI=L and all data inputs HIGH, A2/A1/A0 and GS are HIGH while EO is LOW. When any data input is LOW, the highest-priority active input is encoded and GS is LOW, EO is HIGH.

**Second-source cross-check:** ST [M74HC148](https://www.st.com/resource/en/datasheet/cd00000265.pdf), document CD00000265, pages 1–2, independently confirms DIP pin assignments, active-LOW data input behavior, the same priority ordering, and all ten rows of the truth table. The ST variant is HC rather than LS, but is explicitly pin/function compatible with the 74-series 148. **Not single-source.**

## 7447 — pin/function audit complete; MODEL BLOCKED (BI/RBO bidirectional)

**TI source:** [SN74LS47 shared SN74LS47/SN74LS48 datasheet](https://www.ti.com/lit/ds/symlink/sn74ls47.pdf), SDLS111 (March 1974, revised March 1988), page 1 package pin configuration and page 3 function table T1.

**Package:** SN74LS47 is listed in D or N 16-pin packages; use **DIP16 (N)**.

**DIP16 N top-view pin table (TI page 1):**

| Pin | Signal | Direction / behavior |
|---:|---|---|
| 1 | B | BCD input, weight 2 |
| 2 | C | BCD input, weight 4 |
| 3 | LT | active-LOW lamp-test input |
| 4 | BI/RBO | bidirectional active-LOW blanking input / ripple-blanking output |
| 5 | RBI | active-LOW ripple-blanking input |
| 6 | D | BCD input, weight 8 |
| 7 | A | BCD input, weight 1 |
| 8 | GND | IN |
| 9 | e | segment output |
| 10 | d | segment output |
| 11 | c | segment output |
| 12 | b | segment output |
| 13 | a | segment output |
| 14 | g | segment output |
| 15 | f | segment output |
| 16 | VCC | IN |

**TI function table T1 (page 3):** table columns are function, LT, RBI, D, C, B, A, BI/RBO, a, b, c, d, e, f, g. TI uses ON/OFF for segment states; since LS47 outputs are active-LOW, ON=LOW and OFF=HIGH.

| Function | LT | RBI | D C B A | BI/RBO | a b c d e f g (TI ON/OFF) |
|---:|---|---|---|---|---|
| 0 | H | H | L L L L | H | ON ON ON ON ON ON OFF |
| 1 | H | X | L L L H | H | OFF ON ON OFF OFF OFF OFF |
| 2 | H | X | L L H L | H | ON ON OFF ON ON OFF ON |
| 3 | H | X | L L H H | H | ON ON ON ON OFF OFF ON |
| 4 | H | X | L H L L | H | OFF ON ON OFF OFF ON ON |
| 5 | H | X | L H L H | H | ON OFF ON ON OFF ON ON |
| 6 | H | X | L H H L | H | OFF OFF ON ON ON ON ON |
| 7 | H | X | L H H H | H | ON ON ON OFF OFF OFF OFF |
| 8 | H | X | H L L L | H | ON ON ON ON ON ON ON |
| 9 | H | X | H L L H | H | ON ON ON OFF OFF ON ON |
| 10 | H | X | H L H L | H | OFF OFF OFF ON ON OFF ON |
| 11 | H | X | H L H H | H | OFF OFF ON ON OFF OFF ON |
| 12 | H | X | H H L L | H | OFF ON OFF OFF OFF ON ON |
| 13 | H | X | H H L H | H | ON OFF OFF ON OFF ON ON |
| 14 | H | X | H H H L | H | OFF OFF OFF ON ON ON ON |
| 15 | H | X | H H H H | H | OFF OFF OFF OFF OFF OFF OFF |
| BI | X | X | X X X X | L | OFF OFF OFF OFF OFF OFF OFF |
| RBI | H | L | L L L L | L | OFF OFF OFF OFF OFF OFF OFF |
| LT | L | X | X X X X | H | ON ON ON ON ON ON ON |

H=HIGH, L=LOW, X=irrelevant. TI notes: BI low blanks outputs regardless of other signals; RBI low with BCD zero and LT high blanks zero and asserts RBO low; LT low turns all segments ON when BI/RBO is open/high. BI/RBO is explicitly **wire-AND logic serving as BI and/or RBO**.

**Second-source cross-check:** Fairchild [DM74LS47](https://digsys.upc.edu/csd/chips/classic/74LS47.pdf), DS009817, pages 1–2, independently confirms the N16E DIP pin map, active-LOW open-collector segment outputs, the complete T1 truth table, and the wired-AND/cascading notes. **Not single-source.**

**Model status:** Do not implement with the current component contract. BI/RBO must both accept an external LOW blanking input and sink the internally generated RBO response (wire-AND/open-collector) when RBI and BCD zero are active. ComponentDefinition.Port currently only supports one-way IN or OUT and the simulator has no released/high-impedance net driver semantics. Treating BI/RBO as IN-only or OUT-only would not implement the TI table/cascade behavior exactly.

## 7448 — pin/function audit complete; MODEL BLOCKED (BI/RBO bidirectional)

**TI source:** Same [SN74LS47/SN74LS48 datasheet](https://www.ti.com/lit/ds/symlink/sn74ls47.pdf), SDLS111, page 1 package pin configuration and page 4 function table T2.

**Package:** SN7448 / SN74LS48 is listed in J or N for SN7448 and D or N for SN74LS48; use **DIP16 (N)**.

**DIP16 N top-view pin table:** identical to the T1 map above: 1=B, 2=C, 3=LT (active LOW), 4=BI/RBO (bidirectional active LOW), 5=RBI (active LOW), 6=D, 7=A, 8=GND, 9=e, 10=d, 11=c, 12=b, 13=a, 14=g, 15=f, 16=VCC. TI page 1 shows the pin configuration once for both device families.

**TI function table T2 (page 4):** columns are function, LT, RBI, D, C, B, A, BI/RBO, a, b, c, d, e, f, g. Unlike LS47, LS48 segment outputs are active-HIGH and have internal pull-ups.

| Function | LT | RBI | D C B A | BI/RBO | a b c d e f g |
|---:|---|---|---|---|---|---|
| 0 | H | H | L L L L | H | H H H H H H L |
| 1 | H | X | L L L H | H | L H H L L L L |
| 2 | H | X | L L H L | H | H H L H H L H |
| 3 | H | X | L L H H | H | H H H H L L H |
| 4 | H | X | L H L L | H | L H H L L H H |
| 5 | H | X | L H L H | H | H L H H L H H |
| 6 | H | X | L H H L | H | L L H H H H H |
| 7 | H | X | L H H H | H | H H H L L L L |
| 8 | H | X | H L L L | H | H H H H H H H |
| 9 | H | X | H L L H | H | H H H L L H H |
| 10 | H | X | H L H L | H | L L L H H L H |
| 11 | H | X | H L H H | H | L L H H L L H |
| 12 | H | X | H H L L | H | L H L L L H H |
| 13 | H | X | H H L H | H | H L L H L H H |
| 14 | H | X | H H H L | H | L L L H H H H |
| 15 | H | X | H H H H | H | L L L L L L L |
| BI | X | X | X X X X | L | L L L L L L L |
| RBI | H | L | L L L L | L | L L L L L L L |
| LT | L | X | X X X X | H | H H H H H H H |

H=HIGH, L=LOW, X=irrelevant. The same TI note semantics apply: BI low forces the segment outputs LOW; RBI low with BCD zero asserts RBO low and blanks outputs; LT low makes all segment outputs HIGH if BI/RBO is open/high. BI/RBO is a bidirectional wire-AND node.

**Second-source cross-check:** National Semiconductor [DM74LS48](https://www.hatfon.com/class/INNOVAEditor/assets/Entegreler/74LS48.pdf), TL/F/10172 (January 1992), pages 1–3, confirms the DIP pin map, active-HIGH segment outputs/pull-ups, all T2 rows and BI/RBO wired-AND blanking/ripple notes. **Not single-source.**

**Model status:** Same BI/RBO INOUT/open-collector limitation as 7447. Do not create an input-only or output-only approximation.

## 74151 — audited; implementation pending

**TI source:** [SN74LS151](https://www.ti.com/lit/gpn/SN74LS151), datasheet SDLS054 (December 1972, revised March 1988), 16-pin package pin configuration page 1 and 151A/LS151/S151 function table page 4.

**Package:** SN74LS151 is D or N 16-pin package; use **DIP16 (N)**.

**DIP16 N top-view pin table (TI page 1):**

| Pin | Signal | Direction |
|---:|---|---|
| 1 | D3 | IN |
| 2 | D2 | IN |
| 3 | D1 | IN |
| 4 | D0 | IN |
| 5 | Y | OUT, selected data |
| 6 | W | OUT, complement of selected data |
| 7 | G | IN, active LOW strobe/enable |
| 8 | GND | IN |
| 9 | C | IN, select bit 2 |
| 10 | B | IN, select bit 1 |
| 11 | A | IN, select bit 0 |
| 12 | D7 | IN |
| 13 | D6 | IN |
| 14 | D5 | IN |
| 15 | D4 | IN |
| 16 | VCC | IN |

**TI function table (page 4):**

| C | B | A | G̅ | Y | W |
|---|---|---|---|---|---|
| X | X | X | H | L | H |
| L | L | L | L | D0 | D̅0 |
| L | L | H | L | D1 | D̅1 |
| L | H | L | L | D2 | D̅2 |
| L | H | H | L | D3 | D̅3 |
| H | L | L | L | D4 | D̅4 |
| H | L | H | L | D5 | D̅5 |
| H | H | L | L | D6 | D̅6 |
| H | H | H | L | D7 | D̅7 |

H=HIGH, L=LOW, X=irrelevant. G high disables the selector and forces Y low/W high. With G low, CBA selects D0–D7 and W is the complement of Y.

**Second-source cross-check:** Nexperia [74HC151/74HCT151](https://assets.nexperia.com/documents/data-sheet/74HC_HCT151.pdf), Rev.11 (11 March 2024), pages 2–4, confirms corresponding pin positions, active-LOW enable, complementary outputs, and the same selection rows. Its package options include SO16/TSSOP16/DHVQFN16, so TI remains the authority for the N-package DIP16 assignment. **Not single-source.**

## 74153 — audited; implementation pending

**TI source:** [SN74LS153](https://www.ti.com/lit/ds/symlink/sn74ls153.pdf), datasheet SDLS055A (December 1972, revised May 2007), DIP/N pin configuration and function table on page 1.

**Package:** TI specifies SN74LS153 in D or N package; use **DIP16 (N)**.

**DIP16 N top-view pin table (TI page 1):**

| Pin | Signal | Direction / behavior |
|---:|---|---|
| 1 | 1G | IN, active-LOW strobe |
| 2 | B | IN, shared select bit B |
| 3 | 1C3 | IN, channel 1 data 3 |
| 4 | 1C2 | IN, channel 1 data 2 |
| 5 | 1C1 | IN, channel 1 data 1 |
| 6 | 1C0 | IN, channel 1 data 0 |
| 7 | 1Y | OUT, channel 1 selected data |
| 8 | GND | IN |
| 9 | 2Y | OUT, channel 2 selected data |
| 10 | 2C0 | IN, channel 2 data 0 |
| 11 | 2C1 | IN, channel 2 data 1 |
| 12 | 2C2 | IN, channel 2 data 2 |
| 13 | 2C3 | IN, channel 2 data 3 |
| 14 | A | IN, shared select bit A |
| 15 | 2G | IN, active-LOW strobe |
| 16 | VCC | IN |

**TI function table (page 1), same for channel 1 and channel 2:**

| B | A | C0 | C1 | C2 | C3 | G̅ | Y |
|---|---|---|---|---|---|---|---|
| X | X | X | X | X | X | H | L |
| L | L | L | X | X | X | L | L |
| L | L | H | X | X | X | L | H |
| L | H | X | L | X | X | L | L |
| L | H | X | H | X | X | L | H |
| H | L | X | X | L | X | L | L |
| H | L | X | X | H | X | L | H |
| H | H | X | X | X | L | L | L |
| H | H | X | X | X | H | L | H |

H=HIGH, L=LOW, X=irrelevant. A and B are common select inputs shared by both four-line sections. Each section has a separate active-LOW strobe; strobe HIGH forces that channel output LOW, and with strobe LOW, BA selects C0/C1/C2/C3.

**Second-source cross-check:** Nexperia [74HC153/74HCT153](https://assets.nexperia.com/documents/data-sheet/74HC_HCT153.pdf), Rev.11 (11 March 2024), pages 3–4, independently confirms the corresponding pin positions, both active-LOW enables, shared select lines, and all nine mux rows. Nexperia package drawings are SO16/TSSOP16; TI remains authoritative for DIP16 N. **Not single-source.**

