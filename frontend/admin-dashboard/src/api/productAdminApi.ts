import adminClient from './adminClient'

export interface ProductImage {
  id: number
  imageUrl: string
  altText: string | null
  displayOrder: number
}

export interface ImageOrderItem {
  imageId: number
  displayOrder: number
}

export interface AdminProduct {
  id: number
  sku: string
  name: string
  description: string | null
  price: number
  brand: string | null
  rating: number | null
  stockQuantity: number
  category: { id: number; name: string; description: string | null }
  images: ProductImage[]
  createdAt: string
  updatedAt: string
}

export interface AdminProductListItem {
  id: number
  sku: string
  name: string
  description: string | null
  price: number
  brand: string | null
  rating: number | null
  stockQuantity: number
  categoryId: number
  categoryName: string
  primaryImageUrl: string | null
}

export interface CategoryOption {
  id: number
  name: string
  description: string | null
}

export interface CreateProductPayload {
  name: string
  description: string
  categoryId: number
  price: number
  brand: string
  sku?: string
}

export interface UpdateProductPayload {
  name: string
  description: string
  categoryId: number
  price: number
  brand: string
}

interface PagedResponse<T> {
  data: T[]
  pagination: { page: number; size: number; totalElements: number; totalPages: number }
  traceId: string
}

interface ApiResponse<T> {
  data: T
  traceId: string
}

export async function listProducts(page = 0, size = 25): Promise<PagedResponse<AdminProductListItem>> {
  const { data } = await adminClient.get<PagedResponse<AdminProductListItem>>(
    `/api/v1/products?page=${page}&size=${size}`,
  )
  return data
}

export async function createProduct(payload: CreateProductPayload): Promise<AdminProduct> {
  const { data } = await adminClient.post<ApiResponse<AdminProduct>>(
    '/api/v1/admin/products',
    payload,
  )
  return data.data
}

export async function updateProduct(id: number, payload: UpdateProductPayload): Promise<AdminProduct> {
  const { data } = await adminClient.put<ApiResponse<AdminProduct>>(
    `/api/v1/admin/products/${id}`,
    payload,
  )
  return data.data
}

export async function deleteProduct(id: number): Promise<void> {
  await adminClient.delete(`/api/v1/admin/products/${id}`)
}

export async function getCategories(): Promise<CategoryOption[]> {
  const { data } = await adminClient.get<CategoryOption[]>('/api/v1/admin/categories')
  return data
}

export async function getProductDetail(id: number): Promise<AdminProduct> {
  const { data } = await adminClient.get<ApiResponse<AdminProduct>>(`/api/v1/products/${id}`)
  return data.data
}

export async function uploadImages(productId: number, files: File[]): Promise<ProductImage[]> {
  const formData = new FormData()
  files.forEach(f => formData.append('files', f))
  const { data } = await adminClient.post<ProductImage[]>(
    `/api/v1/admin/products/${productId}/images`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  )
  return data
}

export async function deleteImage(productId: number, imageId: number): Promise<void> {
  await adminClient.delete(`/api/v1/admin/products/${productId}/images/${imageId}`)
}

export async function reorderImages(
  productId: number,
  items: ImageOrderItem[],
): Promise<ProductImage[]> {
  const { data } = await adminClient.put<ProductImage[]>(
    `/api/v1/admin/products/${productId}/images/order`,
    { items },
  )
  return data
}
