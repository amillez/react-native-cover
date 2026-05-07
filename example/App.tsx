import { StatusBar } from "expo-status-bar";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  Image,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { Cover } from "react-native-cover";

const COVER_COLORS = [
  { label: "Black", value: "#000000" },
  { label: "Indigo", value: "#1E1B4B" },
  { label: "Crimson", value: "#7F1D1D" },
  { label: "Transparent", value: "#00000000" },
] as const;

const BLUR_STYLES = [
  { label: "Light", value: "light" as const },
  { label: "Dark", value: "dark" as const },
  { label: "Regular", value: "regular" as const },
  { label: "ExtraLight", value: "extraLight" as const },
];

const FADE_DURATIONS = [
  { label: "Off", value: 0 },
  { label: "200ms", value: 200 },
  { label: "600ms", value: 600 },
] as const;

const RESIZE_MODES = [
  { label: "Cover", value: "cover" as const },
  { label: "Contain", value: "contain" as const },
  { label: "Stretch", value: "stretch" as const },
  { label: "Center", value: "center" as const },
];

const POSITIONS_X = [
  { label: "L", value: "left" as const },
  { label: "C", value: "center" as const },
  { label: "R", value: "right" as const },
];

const POSITIONS_Y = [
  { label: "T", value: "top" as const },
  { label: "C", value: "center" as const },
  { label: "B", value: "bottom" as const },
];

const BLUR_INTENSITIES = [
  { label: "0.2", value: 0.2 },
  { label: "0.4", value: 0.4 },
  { label: "0.7", value: 0.7 },
  { label: "1.0", value: 1.0 },
];

const COVER_IMAGE = Image.resolveAssetSource(require("./assets/icon.png")) as {
  uri: string;
};

export default function App() {
  const [enabled, setEnabled] = useState(Cover.isEnabled);
  const [color, setColor] = useState<string>(COVER_COLORS[0].value);
  const [hasImage, setHasImage] = useState<boolean>(false);
  const [resize, setResize] = useState<
    "cover" | "contain" | "stretch" | "center"
  >("contain");
  const [posX, setPosX] = useState<"left" | "center" | "right">("center");
  const [posY, setPosY] = useState<"top" | "center" | "bottom">("center");
  const [size, setSize] = useState<number>(0); // 0 = full cover; >0 = fixed box (square)
  const [blur, setBlur] = useState<string | null>(null);
  const [blurIntensity, setBlurIntensity] = useState<number>(0.4);
  const [fade, setFade] = useState<number>(FADE_DURATIONS[1].value);
  const [modalVisible, setModalVisible] = useState<boolean>(false);

  useEffect(() => {
    Cover.setFade(fade);
  }, [fade]);

  const toggleEnabled = useCallback(() => {
    if (enabled) {
      Cover.disable();
      setEnabled(false);
    } else {
      Cover.enable();
      setEnabled(true);
    }
  }, [enabled]);

  const pickColor = useCallback((value: string) => {
    Cover.setColor(value);
    setColor(value);
    setBlur(null);
  }, []);

  const applyImage = useCallback(
    (
      r: "cover" | "contain" | "stretch" | "center",
      px: "left" | "center" | "right",
      py: "top" | "center" | "bottom",
      sz: number,
    ) => {
      Cover.setImage({
        uri: COVER_IMAGE.uri,
        resizeMode: r,
        x: px,
        y: py,
        width: sz,
        height: sz,
      });
    },
    [],
  );

  const toggleImage = useCallback(() => {
    if (hasImage) {
      Cover.clearImage();
      setHasImage(false);
    } else {
      applyImage(resize, posX, posY, size);
      setHasImage(true);
      setBlur(null);
    }
  }, [hasImage, resize, posX, posY, size, applyImage]);

  const pickResize = useCallback(
    (value: "cover" | "contain" | "stretch" | "center") => {
      setResize(value);
      if (hasImage) applyImage(value, posX, posY, size);
    },
    [hasImage, posX, posY, size, applyImage],
  );

  const pickPosX = useCallback(
    (value: "left" | "center" | "right") => {
      setPosX(value);
      if (hasImage) applyImage(resize, value, posY, size);
    },
    [hasImage, resize, posY, size, applyImage],
  );

  const pickPosY = useCallback(
    (value: "top" | "center" | "bottom") => {
      setPosY(value);
      if (hasImage) applyImage(resize, posX, value, size);
    },
    [hasImage, resize, posX, size, applyImage],
  );

  const pickSize = useCallback(
    (value: number) => {
      setSize(value);
      if (hasImage) applyImage(resize, posX, posY, value);
    },
    [hasImage, resize, posX, posY, applyImage],
  );

  const pickBlur = useCallback(
    (style: "light" | "dark" | "regular" | "extraLight") => {
      Cover.setBlur(style, blurIntensity);
      setBlur(style);
    },
    [blurIntensity],
  );

  const pickBlurIntensity = useCallback(
    (value: number) => {
      setBlurIntensity(value);
      if (blur) {
        Cover.setBlur(
          blur as "light" | "dark" | "regular" | "extraLight",
          value,
        );
      }
    },
    [blur],
  );

  const hideNow = useCallback(() => {
    Cover.hide();
  }, []);

  const showNow = useCallback(() => {
    Cover.show();
    Alert.alert("Cover shown", "Tap OK to dismiss the cover.", [
      { text: "OK", onPress: hideNow },
    ]);
  }, [hideNow]);

  // Show the cover with no alert and auto-hide after 3 s. Used by the
  // "cover above modal" maestro flow: while the cover is up, taps in
  // the underlying RN Modal must be blocked; the auto-hide gives the
  // test a clean window to verify state once the cover is gone.
  const showCoverAutoHide = useCallback(() => {
    Cover.show();
    setTimeout(() => {
      Cover.hide();
    }, 3000);
  }, []);

  const modeSummary = useMemo(() => {
    if (blur) return `BLUR ${blur.toUpperCase()} ${blurIntensity.toFixed(1)}`;
    const parts: string[] = [];
    parts.push(`COLOR ${color.toUpperCase()}`);
    if (hasImage) {
      const sizeLabel = size > 0 ? `${size}` : "full";
      parts.push(`IMG ${resize}/${posX[0]}${posY[0]}/${sizeLabel}`);
    }
    return parts.join(" + ");
  }, [blur, blurIntensity, color, hasImage, resize, posX, posY, size]);

  return (
    <View style={styles.safe}>
      <StatusBar style="dark" />
      {/* Sticky header — kept outside the ScrollView so status / mode
          are always visible to e2e assertions regardless of scroll. */}
      <View style={styles.header}>
        <Text style={styles.title}>Cover</Text>
        <View style={styles.row}>
          <Text style={styles.label}>Status</Text>
          <Text testID="status-text" style={styles.value}>
            {enabled ? "ENABLED" : "DISABLED"}
          </Text>
        </View>
        <View style={styles.row}>
          <Text style={styles.label}>Mode</Text>
          <Text testID="mode-text" style={styles.value}>
            {modeSummary}
          </Text>
        </View>
      </View>
      <ScrollView
        contentContainerStyle={styles.container}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.subtitle}>
          Color + image stack, or blur. `setColor` and `setImage` combine;
          `setBlur` replaces them visually.
        </Text>

        <Pressable
          testID="toggle-cover"
          onPress={toggleEnabled}
          style={({ pressed }) => [
            styles.button,
            enabled ? styles.buttonOn : styles.buttonOff,
            pressed && styles.buttonPressed,
          ]}
        >
          <Text style={styles.buttonText}>
            {enabled ? "Disable cover" : "Enable cover"}
          </Text>
        </Pressable>

        <Text style={styles.section}>Background color</Text>
        <View style={styles.chipRow}>
          {COVER_COLORS.map((option) => {
            const selected = !blur && color === option.value;
            return (
              <Pressable
                key={option.value}
                testID={`color-${option.label.toLowerCase()}`}
                onPress={() => pickColor(option.value)}
                style={[
                  styles.colorChip,
                  option.value === "#00000000"
                    ? styles.colorChipTransparent
                    : { backgroundColor: option.value },
                  selected && styles.chipSelected,
                ]}
              >
                <Text style={styles.chipLabel}>{option.label}</Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={styles.section}>Image overlay</Text>
        <Pressable
          testID="toggle-image"
          onPress={toggleImage}
          style={[styles.imageButton, hasImage && styles.chipSelected]}
        >
          <Image
            source={{ uri: COVER_IMAGE.uri }}
            style={styles.imagePreview}
          />
          <Text style={styles.imageLabel}>
            {hasImage ? "Remove image overlay" : "Add icon as overlay"}
          </Text>
        </Pressable>

        <Text style={styles.subSection}>Resize</Text>
        <View style={styles.chipRow}>
          {RESIZE_MODES.map((option) => {
            const selected = resize === option.value;
            return (
              <Pressable
                key={option.value}
                testID={`resize-${option.value}`}
                onPress={() => pickResize(option.value)}
                style={[styles.smallChip, selected && styles.chipSelected]}
              >
                <Text style={styles.blurLabel}>{option.label}</Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={styles.subSection}>Position</Text>
        <View style={styles.posRow}>
          <View style={styles.posCol}>
            <Text style={styles.posLabel}>X</Text>
            {POSITIONS_X.map((option) => {
              const selected = posX === option.value;
              return (
                <Pressable
                  key={option.value}
                  testID={`posx-${option.value}`}
                  onPress={() => pickPosX(option.value)}
                  style={[styles.smallChip, selected && styles.chipSelected]}
                >
                  <Text style={styles.blurLabel}>{option.label}</Text>
                </Pressable>
              );
            })}
          </View>
          <View style={styles.posCol}>
            <Text style={styles.posLabel}>Y</Text>
            {POSITIONS_Y.map((option) => {
              const selected = posY === option.value;
              return (
                <Pressable
                  key={option.value}
                  testID={`posy-${option.value}`}
                  onPress={() => pickPosY(option.value)}
                  style={[styles.smallChip, selected && styles.chipSelected]}
                >
                  <Text style={styles.blurLabel}>{option.label}</Text>
                </Pressable>
              );
            })}
          </View>
        </View>

        <Text style={styles.subSection}>Size (square box, 0 = full)</Text>
        <View style={styles.chipRow}>
          {[0, 120, 240].map((value) => {
            const selected = size === value;
            return (
              <Pressable
                key={value}
                testID={`size-${value}`}
                onPress={() => pickSize(value)}
                style={[styles.smallChip, selected && styles.chipSelected]}
              >
                <Text style={styles.blurLabel}>
                  {value === 0 ? "Full" : `${value}`}
                </Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={styles.section}>Blur (replaces color + image)</Text>
        <View style={styles.chipRow}>
          {BLUR_STYLES.map((option) => {
            const selected = blur === option.value;
            return (
              <Pressable
                key={option.value}
                testID={`blur-${option.value.toLowerCase()}`}
                onPress={() => pickBlur(option.value)}
                style={[styles.smallChip, selected && styles.chipSelected]}
              >
                <Text style={styles.blurLabel}>{option.label}</Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={styles.subSection}>Intensity</Text>
        <View style={styles.chipRow}>
          {BLUR_INTENSITIES.map((option) => {
            const selected = blurIntensity === option.value;
            return (
              <Pressable
                key={option.value}
                testID={`blur-intensity-${option.label}`}
                onPress={() => pickBlurIntensity(option.value)}
                style={[styles.smallChip, selected && styles.chipSelected]}
              >
                <Text style={styles.blurLabel}>{option.label}</Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={styles.section}>Fade</Text>
        <View style={styles.chipRow}>
          {FADE_DURATIONS.map((option) => {
            const selected = fade === option.value;
            return (
              <Pressable
                key={option.value}
                testID={`fade-${option.value}`}
                onPress={() => setFade(option.value)}
                style={[styles.smallChip, selected && styles.chipSelected]}
              >
                <Text style={styles.blurLabel}>{option.label}</Text>
              </Pressable>
            );
          })}
        </View>

        <Text style={styles.section}>Manual controls (e2e)</Text>
        <View style={styles.manualRow}>
          <Pressable
            testID="show-cover"
            onPress={showNow}
            style={[styles.button, styles.buttonGhost]}
          >
            <Text style={styles.buttonGhostText}>Show cover now</Text>
          </Pressable>
          <Pressable
            testID="hide-cover"
            onPress={hideNow}
            style={[styles.button, styles.buttonGhost]}
          >
            <Text style={styles.buttonGhostText}>Hide cover now</Text>
          </Pressable>
        </View>

        <Text style={styles.section}>Modal coverage (e2e)</Text>
        <Pressable
          testID="open-modal"
          onPress={() => setModalVisible(true)}
          style={[styles.button, styles.buttonGhost]}
        >
          <Text style={styles.buttonGhostText}>Open modal</Text>
        </Pressable>

        <Text style={styles.hint}>
          Try: enable, pick a color and an image overlay (combined!), pick a
          resize+position, send the app to background, open the App Switcher.
        </Text>
      </ScrollView>

      {/* RN <Modal> on Android renders in a separate top-level Dialog
          window. The "Show cover above modal" button proves the native
          cover paints above this Dialog — used by the maestro flow
          `modal-coverage.yaml`. */}
      <Modal
        visible={modalVisible}
        transparent={false}
        animationType="slide"
        onRequestClose={() => setModalVisible(false)}
      >
        <View style={styles.modalRoot}>
          <Text testID="modal-content" style={styles.modalTitle}>
            MODAL CONTENT
          </Text>
          <Text style={styles.modalBody}>
            This is a React Native Modal (Dialog window on Android). Tapping
            "Show cover" while this modal is open should hide the modal under
            the cover and block taps until the cover auto-dismisses.
          </Text>
          <Pressable
            testID="modal-show-cover"
            onPress={showCoverAutoHide}
            style={[styles.button, styles.buttonOff]}
          >
            <Text style={styles.buttonText}>Show cover (auto-hide 3s)</Text>
          </Pressable>
          <Pressable
            testID="modal-close"
            onPress={() => setModalVisible(false)}
            style={[styles.button, styles.buttonGhost]}
          >
            <Text style={styles.buttonGhostText}>Close modal</Text>
          </Pressable>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: "#FAFAFA" },
  header: {
    paddingHorizontal: 24,
    paddingTop: 64,
    paddingBottom: 12,
    gap: 12,
    backgroundColor: "#FAFAFA",
    borderBottomWidth: 1,
    borderBottomColor: "#E5E7EB",
  },
  container: {
    paddingHorizontal: 24,
    paddingTop: 16,
    paddingBottom: 48,
    gap: 14,
  },
  title: { fontSize: 28, fontWeight: "700", color: "#111827" },
  subtitle: { fontSize: 14, color: "#4B5563", marginBottom: 4 },
  row: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 12,
    paddingHorizontal: 16,
    backgroundColor: "#FFFFFF",
    borderRadius: 12,
    borderWidth: 1,
    borderColor: "#E5E7EB",
  },
  label: { fontSize: 14, color: "#6B7280" },
  value: {
    fontSize: 13,
    fontWeight: "600",
    color: "#111827",
    textAlign: "right",
  },
  button: {
    paddingVertical: 14,
    paddingHorizontal: 18,
    borderRadius: 12,
    alignItems: "center",
  },
  buttonOn: { backgroundColor: "#16A34A" },
  buttonOff: { backgroundColor: "#2563EB" },
  buttonPressed: { opacity: 0.85 },
  buttonText: { color: "#FFFFFF", fontWeight: "600", fontSize: 16 },
  buttonGhost: {
    backgroundColor: "#FFFFFF",
    borderWidth: 1,
    borderColor: "#D1D5DB",
    flex: 1,
  },
  buttonGhostText: { color: "#111827", fontWeight: "600", fontSize: 14 },
  section: {
    fontSize: 12,
    fontWeight: "600",
    color: "#6B7280",
    textTransform: "uppercase",
    letterSpacing: 0.5,
    marginTop: 8,
  },
  subSection: {
    fontSize: 11,
    fontWeight: "600",
    color: "#9CA3AF",
    textTransform: "uppercase",
    letterSpacing: 0.5,
    marginTop: 4,
  },
  chipRow: { flexDirection: "row", gap: 8, flexWrap: "wrap" },
  colorChip: {
    flex: 1,
    minWidth: 70,
    paddingVertical: 16,
    borderRadius: 10,
    alignItems: "center",
    borderWidth: 2,
    borderColor: "transparent",
  },
  colorChipTransparent: {
    backgroundColor: "#FFFFFF",
    borderColor: "#D1D5DB",
    borderStyle: "dashed",
  },
  chipSelected: { borderColor: "#FACC15" },
  chipLabel: { color: "#FFFFFF", fontWeight: "600", fontSize: 12 },
  smallChip: {
    minWidth: 60,
    paddingVertical: 12,
    paddingHorizontal: 14,
    borderRadius: 10,
    alignItems: "center",
    borderWidth: 2,
    borderColor: "transparent",
    backgroundColor: "#E5E7EB",
  },
  blurLabel: { color: "#111827", fontWeight: "600", fontSize: 13 },
  imageButton: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    backgroundColor: "#FFFFFF",
    padding: 12,
    borderRadius: 12,
    borderWidth: 2,
    borderColor: "transparent",
  },
  imagePreview: {
    width: 48,
    height: 48,
    borderRadius: 8,
    backgroundColor: "#000",
  },
  imageLabel: { color: "#111827", fontWeight: "600", fontSize: 14 },
  posRow: { flexDirection: "row", gap: 12 },
  posCol: { flex: 1, gap: 6 },
  posLabel: { fontSize: 11, color: "#9CA3AF", textAlign: "center" },
  manualRow: { flexDirection: "row", gap: 8 },
  hint: { fontSize: 13, color: "#6B7280", marginTop: 12, lineHeight: 18 },
  modalRoot: {
    flex: 1,
    backgroundColor: "#FEF3C7",
    paddingHorizontal: 24,
    paddingTop: 80,
    gap: 16,
  },
  modalTitle: { fontSize: 28, fontWeight: "700", color: "#111827" },
  modalBody: { fontSize: 14, color: "#4B5563", lineHeight: 20 },
});
