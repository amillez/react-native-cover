//
//  CoverColor.swift
//  react-native-cover
//
//  UIColor hex parsing for the public Cover API.
//
//  Accepts `#RGB`, `#RGBA`, `#RRGGBB`, and `#RRGGBBAA`. The 4- and 8-char
//  forms are RGB-then-alpha (CSS convention), matching the existing
//  public API. Names like "red" and the AARRGGBB ordering are
//  intentionally rejected so iOS and Android behave identically.
//

import UIKit

extension UIColor {
  convenience init?(coverHex: String) {
    var s = coverHex.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.hasPrefix("#") { s.removeFirst() }

    let expanded: String
    switch s.count {
    case 3:
      // #RGB → #RRGGBB
      expanded = s.map { "\($0)\($0)" }.joined()
    case 4:
      // #RGBA → #RRGGBBAA
      expanded = s.map { "\($0)\($0)" }.joined()
    case 6, 8:
      expanded = s
    default:
      return nil
    }

    var rgba: UInt64 = 0
    guard Scanner(string: expanded).scanHexInt64(&rgba) else { return nil }

    let r, g, b, a: CGFloat
    if expanded.count == 6 {
      r = CGFloat((rgba & 0xFF0000) >> 16) / 255
      g = CGFloat((rgba & 0x00FF00) >> 8) / 255
      b = CGFloat(rgba & 0x0000FF) / 255
      a = 1.0
    } else {
      r = CGFloat((rgba & 0xFF000000) >> 24) / 255
      g = CGFloat((rgba & 0x00FF0000) >> 16) / 255
      b = CGFloat((rgba & 0x0000FF00) >> 8) / 255
      a = CGFloat(rgba & 0x000000FF) / 255
    }
    self.init(red: r, green: g, blue: b, alpha: a)
  }
}
