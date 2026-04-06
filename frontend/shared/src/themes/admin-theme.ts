import { definePreset } from '@primevue/themes'
import Aura from '@primevue/themes/aura'
import { colors } from '../tokens/colors'

export const adminTheme = definePreset(Aura, {
  semantic: {
    primary: {
      50: colors.primary[50],
      100: colors.primary[100],
      200: colors.primary[200],
      300: colors.primary[300],
      400: colors.primary[400],
      500: colors.primary[500],
      600: colors.primary[600],
      700: colors.primary[700],
      800: colors.primary[800],
      900: colors.primary[900],
      950: colors.primary[900],
    },
    colorScheme: {
      light: {
        surface: {
          0: colors.white,
          50: colors.gray[50],
          100: colors.gray[100],
          200: colors.gray[200],
          300: colors.gray[300],
          400: colors.gray[400],
          500: colors.gray[500],
          600: colors.gray[600],
          700: colors.gray[700],
          800: colors.gray[800],
          900: colors.gray[900],
          950: colors.black,
        },
        primary: {
          color: colors.primary[700],
          contrastColor: colors.white,
          hoverColor: colors.primary[800],
          activeColor: colors.primary[900],
        },
      },
    },
    borderRadius: {
      none: '0',
      xs: '2px',
      sm: '4px',
      md: '4px',
      lg: '6px',
      xl: '8px',
    },
    transitionDuration: '150ms',
  },
})
