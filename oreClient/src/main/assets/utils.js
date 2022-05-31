import config from './config.json5'

export function clearFromEmpty(object) {
  return Object.entries(object)
    .filter(([key, value]) => value != null && value !== '')
    .reduce((acc, [key, value]) => ({ ...acc, [key]: value }), {})
}

export function cleanEmptyObject(object) {
  if (Array.isArray(object) || typeof object !== 'object') {
    return object
  }

  Object.keys(object).forEach((key) => {
    const newValue = cleanEmptyObject(object[key])

    if (newValue === null) {
      delete object[key]
    } else {
      object[key] = newValue
    }
  })

  return Object.entries(object).length ? object : null
}

export function nullIfEmpty(value) {
  return value && value.length ? value : null
}

export function clearFromDefaults(object, defaults) {
  return Object.entries(object)
    .filter(([key, value]) => {
      if (Array.isArray(value) && Array.isArray(defaults[key])) {
        return value.length !== defaults[key].length
      }

      return value !== defaults[key]
    })
    .reduce((acc, [key, value]) => ({ ...acc, [key]: value }), {})
}

export function parseJsonOrNull(jsonString) {
  try {
    return JSON.parse(jsonString)
  } catch (e) {
    return null
  }
}

// https://stackoverflow.com/a/4673436/7207457
export function formatString(format) {
  const args = Array.prototype.slice.call(arguments, 1)
  return format.replace(/{(\d+)}/g, function (match, number) {
    return typeof args[number] !== 'undefined' ? args[number] : match
  })
}

export function avatarUrl(name) {
  if (name === 'Spongie') {
    return config.sponge.logo
  } else {
    return formatString(config.security.api.avatarUrl, name)
  }
}

export function numberWithCommas(x) {
  const parts = x.toString().split('.')
  parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return parts.join('.')
}

export function notFound(self) {
  self.$router.replace(`/404${self.$route.fullPath}`)
}

export function genericError(self, error) {
  self.$store.commit({
    type: 'addAlert',
    level: 'error',
    message: error,
  })
}

export function compact(str) {
  return str.trim().replaceAll(/ +/g, ' ')
}

export function slugify(str) {
  const replaceRegex = /[^a-z\-_.0-9]/g
  const replaced = compact(str).toLowerCase().replaceAll(' ', '-').replaceAll(replaceRegex, '')
  return replaced.substring(0, Math.min(32, replaced.length))
}
