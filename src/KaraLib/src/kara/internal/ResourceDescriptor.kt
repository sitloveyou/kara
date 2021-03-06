package kara.internal

import kara.*
import kotlinx.reflection.buildBeanInstance
import kotlinx.reflection.urlDecode
import org.apache.log4j.Logger
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

val logger = Logger.getLogger(ResourceDescriptor::class.java)!!

/** Contains all the information necessary to match a route and execute an action.
 */
class ResourceDescriptor(val httpMethod: HttpMethod, val route: String, val resourceClass: Class<out Resource>, val allowCrossOrigin: String?) {

    private val routeComponents = route.toRouteComponents()

    // TODO: verify optional components are all last
    private val optionalComponents by lazy { routeComponents.filter { it is OptionalParamRouteComponent }.toList() }

    fun matches(url: String): Boolean {
        val path = url.substringBefore("?")
        val components = path.toPathComponents()
        if (components.size > routeComponents.size || components.size < routeComponents.size - optionalComponents.size)
            return false

        for (i in components.indices) {
            val component = components[i]
            val routeComponent = routeComponents[i]
            if (!routeComponent.matches(component))
                return false
        }
        return true
    }

    fun buildParams(request: HttpServletRequest): RouteParameters {
        val url = request.requestURI?.removePrefix(request.contextPath.orEmpty())!!
        val params = RouteParameters()

        // parse the route parameters
        val pathComponents = url.substringBefore('?').toPathComponents().map { urlDecode(it) }
        if (pathComponents.size < routeComponents.size - optionalComponents.size)
            throw InvalidRouteException("URL has less components than mandatory parameters of the route")
        for (i in pathComponents.indices) {
            val component = pathComponents[i]
            val routeComponent = routeComponents[i]
            routeComponent.setParameter(params, component)
        }

        val isMultiPart = request.contentType?.startsWith("multipart/form-data") ?: false
        if (isMultiPart) {
            for (part in request.parts!!) {
                if (part.size < 4192) {
                    val name = part.name!!
                    params[name] = part.inputStream?.use { it.bufferedReader().readText() } ?: ""
                }
            }
        }

        // parse the request parameters
        val parameterNames = request.parameterNames.toList().filter {
            !isMultiPart || it !in params.parameterNames() // Skip parameters already loaded above on multi part initialization
        }

        for (formParameterName in parameterNames) {
            request.getParameterValues(formParameterName)?.forEach {
                params[formParameterName] = it
            }
        }

        return params
    }

    /** Execute the action based on the given request and populate the response. */
    fun exec(context: ApplicationContext, request: HttpServletRequest, response: HttpServletResponse) {
        val params = buildParams(request)
        val routeInstance = try {
            resourceClass.kotlin.buildBeanInstance(params._map)
        }
        catch(e: RuntimeException) {
            throw e
        }
        catch (e: Exception) {
            throw RuntimeException("Error processing ${request.method} ${request.requestURI}, parameters={$params}, User agent: ${request.getHeader("User-Agent")}", e)
        }

        val actionContext = ActionContext(context, request, response, params, resourceClass.isAnnotationPresent(NoSession::class.java).not())

        actionContext.withContext {
            val actionResult = when {
                allowCrossOrigin == "" && params.optListParam(ActionContext.SESSION_TOKEN_PARAMETER)?.distinct()?.singleOrNull() != actionContext.sessionToken() ->
                    ErrorResult(403, "This request is only valid within same origin")
                else -> {
                    if(!allowCrossOrigin.isNullOrEmpty()) {
                        response.addHeader("Access-Control-Allow-Origin", allowCrossOrigin)
                    }
                    routeInstance.handle(actionContext)
                }
            }
            actionContext.flushSessionCache()
            actionResult.writeResponse(actionContext)
        }
    }

    override fun toString(): String {
        return "Resource<$resourceClass> at $route"
    }

}

