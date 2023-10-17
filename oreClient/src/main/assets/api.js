import queryString from 'query-string'

import { parseJsonOrNull } from './utils'
import config from './config.json5'
import { store } from './stores/index'

export class API {
  static async versionRequest(projectNamespace, version, url, method = 'GET', data = {}) {
    const slashUrl = url && url.length ? '/' + url : url ?? ''
    return await this.projectRequest(projectNamespace, 'versions/' + version + slashUrl, method, data)
  }

  static async projectRequest(namespace, url, method = 'GET', data = {}) {
    const slashUrl = url && url.length ? '/' + url : url ?? ''
    return await this.request('projects/' + namespace.owner + '/' + namespace.slug + slashUrl, method, data)
  }

  static async request(url, method = 'GET', data = {}, secondTry) {
    const session = await this.getSession()

    const isFormData = data instanceof FormData
    const isBodyRequest = method === 'POST' || method === 'PUT' || method === 'PATCH'

    const query = isFormData || isBodyRequest || !Object.entries(data).length ? '' : '?' + queryString.stringify(data)

    const body = isBodyRequest ? (isFormData ? data : JSON.stringify(data)) : undefined

    const headers = { Authorization: 'OreApi session=' + session }
    if (!isFormData) {
      headers['Content-Type'] = 'application/json'
    }
    if (typeof csrf !== 'undefined') {
      headers['Csrf-Token'] = csrf
    }

    const res = await fetch(config.app.baseUrl + '/api/v2/' + url + query, {
      method,
      headers,
      body,
      // Technically not needed, but some internal compat stuff assumes cookies will be present
      mode: 'cors',
      credentials: 'include',
    })

    if (res.ok) {
      if (res.status !== 204) {
        return await res.json()
      } else {
        return {}
      }
    } else if (res.status === 401) {
      const jsonError = await res.json()
      if ((jsonError.error === 'Api session expired' || jsonError.error === 'Invalid session') && !secondTry) {
        // This should never happen but just in case we catch it and invalidate the session to definitely get a new one
        API.invalidateSession()
        return await API.request(url, method, data, true)
      }

      throw res.status
    } else if (res.status === 400) {
      const jsonError = await res.json()
      if (jsonError.user_error) {
        store.commit({
          type: 'addAlert',
          level: 'error',
          message: jsonError.user_error,
        })
      } else if (jsonError.api_error) {
        store.commit({
          type: 'addAlert',
          level: 'error',
          message: jsonError.api_error,
        })
      } else if (jsonError.api_errors) {
        store.commit({
          type: 'addAlerts',
          level: 'error',
          messages: jsonError.api_errors,
        })
      } else if (jsonError.error) {
        store.commit({
          type: 'addAlert',
          level: 'error',
          message: jsonError.error,
        })
      }

      throw res.status
    } else {
      throw res.status
    }
  }

  static async getSession() {
    const session = this.getStoredSession()

    if (session) {
      return session.session
    }

    if (this.hasUser()) {
      const response = await fetch(config.app.baseUrl + '/api/v2/authenticate/user', {
        method: 'POST',
        mode: 'cors',
        credentials: 'include',
        headers: {
          'Csrf-Token': typeof csrf !== 'undefined' ? csrf : undefined,
        },
      })
      if (!response.ok) {
        throw response.statusText
      }

      const data = await response.json()

      if (data.type !== 'user') {
        throw new Error('Expected user session from user authentication')
      } else {
        localStorage.setItem('api_session', JSON.stringify(data))
        return data.session
      }
    } else {
      const response = await fetch(config.app.baseUrl + '/api/v2/authenticate', {
        method: 'POST',
      })
      if (!response.ok) {
        throw response.statusText
      }

      const data = await response.json()

      if (data.type !== 'public') {
        throw new Error('Expected public session from public authentication')
      } else {
        localStorage.setItem('public_api_session', JSON.stringify(data))
        return data.session
      }
    }
  }

  static getStoredSession() {
    const nowWithPadding = new Date()
    nowWithPadding.setTime(nowWithPadding.getTime() + 60000)
    let session

    if (this.hasUser()) {
      session = parseJsonOrNull(localStorage.getItem('api_session'))
    } else {
      session = parseJsonOrNull(localStorage.getItem('public_api_session'))
    }

    if (session !== null && !isNaN(new Date(session.expires).getTime()) && new Date(session.expires) < nowWithPadding) {
      session = null
    }

    return session
  }

  static hasUser() {
    return window.isLoggedIn || config.alwaysTryLogin
  }

  static invalidateSession() {
    if (window.isLoggedIn) {
      localStorage.removeItem('api_session')
    } else {
      localStorage.removeItem('public_api_session')
    }
  }
}
