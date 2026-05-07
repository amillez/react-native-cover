//
//  CoverEnumMappings.swift
//  react-native-cover
//

import UIKit

/// Direct 1:1 mapping from `CoverBlurStyle` to `UIBlurEffect.Style`.
extension CoverBlurStyle {
  var uiKitStyle: UIBlurEffect.Style {
    switch self {
    case .light:      return .light
    case .extralight: return .extraLight
    case .regular:    return .regular
    case .dark:       return .dark
    }
  }
}

extension CoverEasing {
  var uiKitOption: UIView.AnimationOptions {
    switch self {
    case .linear:    return .curveLinear
    case .easein:    return .curveEaseIn
    case .easeout:   return .curveEaseOut
    case .easeinout: return .curveEaseInOut
    }
  }
}
