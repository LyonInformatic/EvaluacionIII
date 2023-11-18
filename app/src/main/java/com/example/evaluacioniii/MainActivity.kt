package com.example.evaluacioniii

import android.Manifest
import android.content.Context
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberImagePainter
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.time.LocalDateTime

enum class Pantalla {
    FORM,
    FOTO
}
class CameraAppViewModel : ViewModel(){
    val pantalla = mutableStateOf(Pantalla.FORM)

    var onPermisoCamaraOk    : () -> Unit = {}
    var onPermisoUbicacionOk : () -> Unit = {}

    var lanzadorPermisos: ActivityResultLauncher<Array<String>>? = null

    fun cambiarPantallaFoto(){ pantalla.value = Pantalla.FOTO}
    fun cambiarPantallaForm(){ pantalla.value = Pantalla.FORM}
}

class FormRecepcionViewModel : ViewModel(){
    data class RecepcionData(
        val lugar: String,
        val latitud: Double,
        val longitud: Double,
        val fotoUri: Uri
    )

    val recepciones = mutableStateListOf<RecepcionData>()

    val lugar      = mutableStateOf("")
    val latitud       = mutableStateOf(0.0)
    val longitud      = mutableStateOf(0.0)
    val fotoRecepcion = mutableStateOf<Uri?>(null)
}

class MainActivity : ComponentActivity(){
    val cameraAppWm:CameraAppViewModel by viewModels()

    lateinit var cameraController: LifecycleCameraController

    val lanzadorPermisos =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
        {
            when{
                (it[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false) or
                        (it[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false) ->{
                            Log.v("callbackRequestMultiple", "permiso ubicacion granted")
                            cameraAppWm.onPermisoUbicacionOk()
                        }
                (it[android.Manifest.permission.CAMERA] ?: false) -> {
                    Log.v("callbackRequestMultiple", "permiso camara granted")
                    cameraAppWm.onPermisoCamaraOk
                }
                else -> {
                    Log.v("callbackRequestMultiple", "permisos no otorgados, cerrando la aplicación")

                    Toast.makeText(this, "Los permisos no fueron otorgados. Cerrando la aplicación.", Toast.LENGTH_SHORT).show()

                    finish()
                    System.exit(0)

                }
            }
        }

    private fun setupCamara() {
        cameraController = LifecycleCameraController(this)
        cameraController.bindToLifecycle(this)
        cameraController.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraAppWm.lanzadorPermisos = lanzadorPermisos
        setupCamara()

        setContent {
            AppUI(cameraController)
        }
    }
}
fun generarNombreSegunFechaHastaSegundo():String = LocalDateTime
    .now().toString().replace(Regex("[T:.-]"),"").substring(0, 14)

fun crearArchivoImagenPublico(contexto: Context): File {
    return File(
        contexto.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        "${generarNombreSegunFechaHastaSegundo()}.jpg"
    )
}

fun tomarFotografia(
    cameraController: CameraController,
    archivo: File,
    contexto: Context,
    formRecepcionVM: FormRecepcionViewModel,
    imagenGuardadaOk: (uri: Uri) -> Unit
) {
    val outputFileOptions = ImageCapture.OutputFileOptions.Builder(archivo).build()
    cameraController.takePicture(
        outputFileOptions,
        ContextCompat.getMainExecutor(contexto),
        object : OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                outputFileResults.savedUri?.also { uri ->
                    Log.v("tomarFotografia()::onImageSaved", "Foto guardada en ${uri.toString()}")
                    imagenGuardadaOk(uri)

                    // Guardar datos en ViewModel
                    val lugar = formRecepcionVM.lugar.value
                    val latitud = formRecepcionVM.latitud.value
                    val longitud = formRecepcionVM.longitud.value

                    formRecepcionVM.recepciones.add(
                        FormRecepcionViewModel.RecepcionData(
                            lugar = lugar,
                            latitud = latitud,
                            longitud = longitud,
                            fotoUri = uri
                        )
                    )
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("tomarFotografia()", "Error: ${exception.message}")
            }
        }
    )
}


class sinPermisoException(mensaje:String) : Exception(mensaje)

fun getUbicacion(contexto: Context, onUbicacionOk:(location:Location)->Unit):Unit{
    try {
        val servicio = LocationServices.getFusedLocationProviderClient(contexto)
        val tarea = servicio.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        tarea.addOnSuccessListener {
            onUbicacionOk(it)
        }
    } catch (e:SecurityException){
        throw sinPermisoException(e.message?:"No tiene permisos para conseguir la ubicacion")
    }
}

@Composable
fun AppUI(cameraController: CameraController){
    val contexto = LocalContext.current
    val formRecepcionVm:FormRecepcionViewModel = viewModel()
    val cameraAppViewModel:CameraAppViewModel = viewModel()

    when(cameraAppViewModel.pantalla.value){
        Pantalla.FORM-> {
            PantallaFormUI(
                formRecepcionVm,
                tomarFotoOnClick ={
                    cameraAppViewModel.cambiarPantallaFoto()

                    cameraAppViewModel.lanzadorPermisos?.launch(arrayOf(Manifest.permission.CAMERA))


                },
                actualizarUbicacionOnClick = {
                    cameraAppViewModel.onPermisoUbicacionOk = {
                        getUbicacion(contexto){
                            formRecepcionVm.latitud.value = it.latitude
                            formRecepcionVm.longitud.value = it.longitude

                        }
                    }
                    cameraAppViewModel.lanzadorPermisos?.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            )
        }
        Pantalla.FOTO->{
            PantallaFotoUI(formRecepcionVm, cameraAppViewModel, cameraController)
        }
        else -> {
            Log.v("AppUI()", "when else, no deberia entrar aqui")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaFormUI(formRecepcionVM:FormRecepcionViewModel, tomarFotoOnClick:() -> Unit = {},
                   actualizarUbicacionOnClick:()-> Unit = {}) {



    Column (
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ){
        TextField(
            label = { Text("Ingresa Lugar Visitado")},
            value = formRecepcionVM.lugar.value,
            onValueChange = {formRecepcionVM.lugar.value =it},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
        )
        Button(onClick = {
            tomarFotoOnClick()
        } ){
            Text("Tomar Footografia")
        }
        Button(onClick = {
            actualizarUbicacionOnClick()

        }){
            Text("Actualizar Ubicacion")
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp),
            horizontalAlignment = Alignment.CenterHorizontally

        ) {
            items(formRecepcionVM.recepciones) { recepcion ->
                val isExpanded = remember { mutableStateOf(false) }
                // Mostrar información de cada recepción
                Text("Lugar: ${recepcion.lugar}")
                Text("Ubicación: lat=${recepcion.latitud}, long=${recepcion.longitud}")
                Image(
                    painter = rememberImagePainter(recepcion.fotoUri),
                    contentDescription = "Imagen Lugar visitado ${recepcion.lugar}",
                    modifier = Modifier.size(200.dp, 100.dp)
                        .clickable {
                            isExpanded.value = true
                        }


                )
                if (isExpanded.value) {
                    ImageFullScreenDialog(recepcion.fotoUri) {
                        isExpanded.value = false
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
            MapOsmUI(formRecepcionVM.latitud.value, formRecepcionVM.longitud.value)
    }
}

@Composable
fun PantallaFotoUI(formRecepcionVM: FormRecepcionViewModel, appViewModel: CameraAppViewModel,
                    cameraController: CameraController){
    val contexto = LocalContext.current

    AndroidView(
        factory = {
            PreviewView(it).apply {
                controller = cameraController
            }
        },
        modifier = Modifier.fillMaxSize()
        )
    Button(onClick = {
            tomarFotografia(
            cameraController,
            crearArchivoImagenPublico(contexto),
                contexto,
            formRecepcionVM,
                imagenGuardadaOk =
        {
            formRecepcionVM.fotoRecepcion.value = it
            appViewModel.cambiarPantallaForm()
        })
    }){
        Text("tomar foto")
    }
}
@Composable
fun MapOsmUI(latitud:Double, longitud:Double){
    val contexto = LocalContext.current

    AndroidView(
        factory = {
            MapView(it).also{
                it.setTileSource(TileSourceFactory.MAPNIK)
                Configuration.getInstance().userAgentValue = contexto.packageName

            }

        },

            update = {
            it.overlays.removeIf{true}
            it.invalidate()

            it.controller.setZoom(18.0)

            val geoPoint = GeoPoint(latitud, longitud)
            it.controller.animateTo(geoPoint)

            val marcador = Marker(it)
            marcador.position = geoPoint
            marcador.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_CENTER)
            it.overlays.add(marcador)
        }
    )
}

@Composable
fun ImageFullScreenDialog(imageUrl: Uri, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
            .clickable {
                       onClose.invoke()
            },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = rememberImagePainter(imageUrl),
                contentDescription = "Imagen Lugar visitado",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}















