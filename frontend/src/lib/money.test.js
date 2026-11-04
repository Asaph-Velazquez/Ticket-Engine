import { describe, expect, it } from "vitest";
import { toMinorUnits } from "./money.js";

describe("Importes exactos", () => {
  it("convierte decimal y coma sin redondeo binario", () => {
    for (const [value, expected] of [
      ["0", 0],
      ["0.01", 1],
      ["0.29", 29],
      ["1.1", 110],
      ["1250,25", 125025],
      ["99999999999.99", 9999999999999],
    ]) {
      expect(toMinorUnits(value)).toBe(expected);
    }
  });
  it("rechaza negativos, precisión extra, exponentes y montos fuera de contrato", () => {
    for (const value of [
      "",
      "-1",
      "1.001",
      "1e3",
      "NaN",
      "1 000",
      "1,234.50",
      "100000000000.00",
      "0x10",
    ]) {
      expect(() => toMinorUnits(value)).toThrow();
    }
  });
});
