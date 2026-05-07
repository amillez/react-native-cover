import { NitroModules } from "react-native-nitro-modules";
import type { Cover as CoverSpec } from "./specs/cover.nitro";

export const Cover = NitroModules.createHybridObject<CoverSpec>("Cover");

export type CoverModule = CoverSpec;

export type {
  CoverBlurStyle,
  CoverEasing,
  CoverImageOptions,
  CoverPositionX,
  CoverPositionY,
  CoverResizeMode,
} from "./specs/cover.nitro";
